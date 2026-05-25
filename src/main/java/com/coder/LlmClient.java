package com.coder;

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

    // 工具参数在 SSE 流中拼完整后，立即通知上层可以开始执行。
    public interface ToolReady {
        // index 是本轮响应中的工具顺序；toolCall 是已经能执行的工具调用。
        void accept(int index, ToolCall toolCall);
    }

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

    // 如果工具已经启动，后面 HTTP 流出错，就不能自动重试。否则可能出现：
    // 第一次请求已经执行 Write，请求中途报错，自动重试，Write 又执行一次
    public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) throws IOException {
        // 记录本次请求期间是否已经提前启动过工具。
        boolean[] toolStarted = {false};
        // 包一层回调，用来在真正通知上层前标记“工具已启动”。
        ToolReady ready = onToolReady == null ? null : (idx, tc) -> {
            // 一旦工具提前执行，后续请求失败时不能自动重试，避免重复执行写操作。
            toolStarted[0] = true;
            // 把工具顺序和完整工具调用交给 Agent。
            onToolReady.accept(idx, tc);
        };
        try {
            // 请求llm，include_usage：要求流式返回中附带 token 用量信息
            return request(messages, tools, onToken, ready, true);

            //请求中途报错
        } catch (IOException e) {
            // 如果工具已经启动，不能重放整个请求。
            if (toolStarted[0]) throw e;
            // 如果还没启动工具，可以保留原有兼容逻辑：不带 usage 再请求一次。
            return request(messages, tools, onToken, ready, false);
        }
    }

    // 核心请求方法
    private Response request(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady, boolean includeUsage) throws IOException {
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

        // 3. 同步执行请求并检查响应,try-with自动关闭响应资源
        try (okhttp3.Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("LLM HTTP " + res.code() + ": " + (res.body() == null ? "" : res.body().string()));
            if (res.body() == null) throw new IOException("LLM 响应为空");

            // 4. 初始化累计器
            // 拼接普通回答文本
            StringBuilder content = new StringBuilder();
            // 拼接推理文本
            StringBuilder reasoning = new StringBuilder();
            // toolMap：按 index 收集流式工具调用片段，用 TreeMap 是为了按索引排序
            Map<Integer, PartialToolCall> toolMap = new TreeMap<>();
            // 记录 token 数
            int prompt = 0;
            int completion = 0;

            // 5. 读取 SSE 流
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
                
                // 6. 解析 JSON
                JsonNode root = JSON.readTree(data);
                // 如果流式响应里带了 token 统计，就读取prompt_tokens，completion_tokens
                JsonNode usage = root.get("usage");
                if (usage != null && !usage.isNull()) {
                    prompt = usage.path("prompt_tokens").asInt(0);
                    completion = usage.path("completion_tokens").asInt(0);
                }

                // 7a. 解析 choices：模型返回的候选结果数组，这里只处理第一个 choice：choices.get(0)
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) continue;
                // 7b. 解析 delta：流式增量内容
                JsonNode delta = choices.get(0).path("delta");
                String token = delta.path("content").asText(null);
                // 如果有新文本 token，边生成边显示
                if (token != null) {
                    // 追加到 content
                    content.append(token);
                    // 如果传了 onToken 回调，就立即输出这个 token
                    if (onToken != null) onToken.accept(token);
                }

                // 8. 解析 reasoning 内容
                String reasoningToken = delta.path("reasoning_content").asText(null);
                if (reasoningToken != null) reasoning.append(reasoningToken);

                // 9. 处理流式工具调用
                JsonNode calls = delta.get("tool_calls");
                if (calls != null && calls.isArray()) {
                    for (JsonNode c : calls) {
                        // 当前工具调用在数组中的位置
                        int idx = c.path("index").asInt();
                        // 如果这个 index 的工具调用还没创建，就新建一个
                        // 流式工具调用常常不是一次性完整返回，而是分片返回：
                        // 第一次给你 id，后面给你 function.name，再后面把 arguments JSON 字符串一段段吐出来
                        // 所以需要一个临时对象 PartialToolCall 来“拼接”
                        PartialToolCall p = toolMap.computeIfAbsent(idx, ignored -> new PartialToolCall());
                        // 如果本片段里带了 id，就保存
                        if (c.hasNonNull("id")) p.id = c.get("id").asText();
                        // 取出 function 对象
                        JsonNode fn = c.get("function");
                        // 如果 function 存在
                        if (fn != null) {
                            // name 有就记下来
                            if (fn.hasNonNull("name")) p.name = fn.get("name").asText();
                            // arguments 有就不断追加到 StringBuilder args
                            if (fn.hasNonNull("arguments")) p.args.append(fn.get("arguments").asText());
                        }

                        // 只有 Agent 传入了工具就绪回调时，才尝试解析并提前通知。
                        if (onToolReady != null) {
                            // 如果当前 arguments 已经是完整 JSON，就得到一个可执行工具调用。
                            // 关键点是：不是等 [DONE]，而是每次 arguments 增加后都试一下
                            ToolCall ready = p.readyToolCall();
                            if (ready != null) {
                                // 标记该 index 已通知过，避免后续 chunk 触发重复执行。
                                p.notified = true;
                                // JSON 已经完整，就立即通知上层执行工具
                                onToolReady.accept(idx, ready);
                            }
                        }
                    }
                }
            }

            // 10. 循环结束后组装最终工具调用列表
            // 即使工具已经提前开始执行，LlmClient 仍然返回完整 toolCalls
            List<ToolCall> calls = toolMap.entrySet().stream()
                    // 把临时收集的工具调用按 index 排序
                    .sorted(Comparator.comparingInt(Map.Entry::getKey))
                    // 流式拼出来的临时工具调用，转换成最终的 ToolCall
                    .map(e -> e.getValue().toToolCall())
                    .toList();
            // 累加全局 prompt token 统计。
            totalPromptTokens += prompt;
            // 累加全局 completion token 统计。
            totalCompletionTokens += completion;
            // 返回llm 本次完整结果
            return new Response(content.toString(), reasoning.toString(), calls, prompt, completion);
        }
    }

    // 拼接 API 地址 endpoint(),兼容不同 API 基础地址
    private String endpoint() {
        String root = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com/v1" : baseUrl;
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root + "/chat/completions";
    }

    // 对象转 JSON
    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // 流式中间态”的临时结构
    private static class PartialToolCall {
        String id = "";
        String name = "";
        StringBuilder args = new StringBuilder();
        // 防止同一个工具调用在流式拼接过程中被重复通知。
        boolean notified;

        // 尝试把当前已拼接的 arguments 当作完整 JSON 解析。
        ToolCall readyToolCall() {
            // 已通知过或还没有工具名，都不能执行。
            if (notified || name.isBlank()) return null;
            try {
                // JSON 能解析成功，说明当前工具参数已经完整。
                Map<String, Object> parsed = JSON.readValue(args.toString(), new TypeReference<>() {});
                // 返回一个可立即执行的工具调用。
                return new ToolCall(id, name, parsed);
            } catch (Exception e) {
                // JSON 还没拼完整时会走这里，继续等待后续 chunk。
                return null;
            }
        }

        // 流式拼出来的临时工具调用，转换成最终的 ToolCall
        // 和 readyToolCall() 的区别是：
        // readyToolCall()：流式过程中试探 JSON 是否已经完整，成功才返回，否则返回 null
        // toToolCall()：SSE 全部结束后最终转换，失败就用空参数兜底返回工具调用
        ToolCall toToolCall() {
            // PartialToolCall 是中间态，里面的 args 还是字符串
            // 准备一个 Map，用来放解析后的工具参数
            Map<String, Object> parsed;
            try {
                // 把累计的 args 字符串当 JSON 解析成 Map<String, Object>
                // 这里用 TypeReference<>() {} 是为了让 Jackson 知道目标类型是泛型 Map，而不是普通 Object
                parsed = JSON.readValue(args.toString(), new TypeReference<>() {});
            } catch (Exception e) {
                parsed = Map.of();
            }
            return new ToolCall(id, name, parsed);
        }
    }
}
