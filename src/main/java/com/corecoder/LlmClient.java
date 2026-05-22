package com.corecoder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public class LlmClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private final OkHttpClient http = new OkHttpClient();
    public final String model;
    public final String apiKey;
    public final String baseUrl;
    public final double temperature;
    public final int maxTokens;
    public int totalPromptTokens;
    public int totalCompletionTokens;

    public record ToolCall(String id, String name, Map<String, Object> arguments) {}
    public record Response(String content, String reasoningContent, List<ToolCall> toolCalls, int promptTokens, int completionTokens) {
        Map<String, Object> message() {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "assistant");
            msg.put("content", content == null || content.isEmpty() ? null : content);
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                msg.put("reasoning_content", reasoningContent);
            }
            if (!toolCalls.isEmpty()) {
                msg.put("tool_calls", toolCalls.stream().map(tc -> Map.of(
                        "id", tc.id(),
                        "type", "function",
                        "function", Map.of("name", tc.name(), "arguments", toJson(tc.arguments()))
                )).toList());
            }
            return msg;
        }
    }

    public LlmClient(String model, String apiKey, String baseUrl, double temperature, int maxTokens) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken) throws IOException {
        try {
            return request(messages, tools, onToken, true);
        } catch (IOException e) {
            return request(messages, tools, onToken, false);
        }
    }

    private Response request(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, boolean includeUsage) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);
        if (includeUsage) body.put("stream_options", Map.of("include_usage", true));

        Request req = new Request.Builder()
                .url(endpoint())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(toJson(body), JSON_TYPE))
                .build();

        try (okhttp3.Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("LLM HTTP " + res.code() + ": " + (res.body() == null ? "" : res.body().string()));
            if (res.body() == null) throw new IOException("LLM 响应为空");
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            Map<Integer, PartialToolCall> toolMap = new TreeMap<>();
            int prompt = 0;
            int completion = 0;

            var source = res.body().source();
            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null || !line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JsonNode root = JSON.readTree(data);
                JsonNode usage = root.get("usage");
                if (usage != null && !usage.isNull()) {
                    prompt = usage.path("prompt_tokens").asInt(0);
                    completion = usage.path("completion_tokens").asInt(0);
                }
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) continue;
                JsonNode delta = choices.get(0).path("delta");
                String token = delta.path("content").asText(null);
                if (token != null) {
                    content.append(token);
                    if (onToken != null) onToken.accept(token);
                }
                String reasoningToken = delta.path("reasoning_content").asText(null);
                if (reasoningToken != null) reasoning.append(reasoningToken);
                JsonNode calls = delta.get("tool_calls");
                if (calls != null && calls.isArray()) {
                    for (JsonNode c : calls) {
                        int idx = c.path("index").asInt();
                        PartialToolCall p = toolMap.computeIfAbsent(idx, ignored -> new PartialToolCall());
                        if (c.hasNonNull("id")) p.id = c.get("id").asText();
                        JsonNode fn = c.get("function");
                        if (fn != null) {
                            if (fn.hasNonNull("name")) p.name = fn.get("name").asText();
                            if (fn.hasNonNull("arguments")) p.args.append(fn.get("arguments").asText());
                        }
                    }
                }
            }

            List<ToolCall> calls = toolMap.entrySet().stream()
                    .sorted(Comparator.comparingInt(Map.Entry::getKey))
                    .map(e -> e.getValue().toToolCall())
                    .toList();
            totalPromptTokens += prompt;
            totalCompletionTokens += completion;
            return new Response(content.toString(), reasoning.toString(), calls, prompt, completion);
        }
    }

    private String endpoint() {
        String root = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com/v1" : baseUrl;
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root + "/chat/completions";
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static class PartialToolCall {
        String id = "";
        String name = "";
        StringBuilder args = new StringBuilder();

        ToolCall toToolCall() {
            Map<String, Object> parsed;
            try {
                parsed = JSON.readValue(args.toString(), new TypeReference<>() {});
            } catch (Exception e) {
                parsed = Map.of();
            }
            return new ToolCall(id, name, parsed);
        }
    }
}
