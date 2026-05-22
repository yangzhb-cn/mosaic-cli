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
    public int totalPromptTokens;
    public int totalCompletionTokens;

    // 一次工具调用
    public record ToolCall(String id, String name, Map<String, Object> arguments) {}
    // 一次响应结果
    public record Response(String content, String reasoningContent, List<ToolCall> toolCalls, int promptTokens, int completionTokens) {
        // 把 Response 转成一个适合塞回对话历史的消息对象
        Map<String, Object> message() {
            Map<String, Object> msg = new LinkedHashMap<>();

            // assistant 消息
            msg.put("role", "assistant");
            msg.put("content", content == null || content.isEmpty() ? null : content);

            // 有推理内容，就加入 reasoning_content 字段
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                msg.put("reasoning_content", reasoningContent);
            }

            // 有工具调用，则生成 tool_calls 数组
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

    public LlmClient(String model, String apiKey, String baseUrl, double temperature) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
    }

    // 对外主入口
    // onToken：每收到一个 token 时回调，用于流式输出
    public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken) throws IOException {
        try {
            // include_usage：要求流式返回中附带 token 用量信息
            return request(messages, tools, onToken, true);
        } catch (IOException e) {
            return request(messages, tools, onToken, false);
        }
    }

    // 核心请求方法
    private Response request(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, boolean includeUsage) throws IOException {
        // 1. 构造请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", temperature);
        // 可选工具定义
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);
        // 要求流式返回中附带 token 用量信息
        if (includeUsage) body.put("stream_options", Map.of("include_usage", true));

        // 2. 构造 HTTP 请求
        Request req = new Request.Builder()
                .url(endpoint())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(toJson(body), JSON_TYPE))
                .build();

        // 3. 同步执行请求并检查响应
        try (okhttp3.Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("LLM HTTP " + res.code() + ": " + (res.body() == null ? "" : res.body().string()));
            if (res.body() == null) throw new IOException("LLM 响应为空");

            // 拼接普通回答文本
            StringBuilder content = new StringBuilder();
            // 拼接推理文本
            StringBuilder reasoning = new StringBuilder();
            // toolMap：按 index 收集流式工具调用片段，用 TreeMap 是为了按索引排序
            Map<Integer, PartialToolCall> toolMap = new TreeMap<>();
            // 记录 token 数
            int prompt = 0;
            int completion = 0;

            // 读取 SSE 流
            var source = res.body().source();
            // 不断读取流式内容，直到结束
            while (!source.exhausted()) {
                // 一行一行读 SSE 数据
                String line = source.readUtf8Line();
                
                // 只处理以 data: 开头的行
                if (line == null || !line.startsWith("data:")) continue;
                // 去掉前缀后，跳过空行和 [DONE]
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                
                // 解析 JSON
                JsonNode root = JSON.readTree(data);
                // 如果流式响应里带了 token 统计，就读取prompt_tokens，completion_tokens
                JsonNode usage = root.get("usage");
                if (usage != null && !usage.isNull()) {
                    prompt = usage.path("prompt_tokens").asInt(0);
                    completion = usage.path("completion_tokens").asInt(0);
                }

                // choices：模型返回的候选结果数组，这里只处理第一个 choice：choices.get(0)
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) continue;
                // delta：流式增量内容
                JsonNode delta = choices.get(0).path("delta");
                String token = delta.path("content").asText(null);
                // 如果有新文本 token，边生成边显示
                if (token != null) {
                    // 追加到 content
                    content.append(token);
                    // 如果传了 onToken 回调，就立即输出这个 token
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
