package com.corecoder;

import com.corecoder.tools.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Agent {
    final LlmClient llm;
    final List<Tools.Tool> tools;
    public final ContextManager context;
    public final List<Map<String, Object>> messages = new ArrayList<>();
    private final int maxRounds;
    private final String system;

    public Agent(LlmClient llm, int maxContextTokens) {
        this.llm = llm;
        this.context = new ContextManager(maxContextTokens);
        // 最多允许模型-工具循环多少轮
        this.maxRounds = 50;
        this.tools = Tools.all(this);
        this.system = Prompt.systemPrompt(tools);
    }

    Agent(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, int maxRounds) {
        this.llm = llm;
        this.tools = tools;
        this.context = new ContextManager(maxContextTokens);
        this.maxRounds = maxRounds;
        this.system = Prompt.systemPrompt(tools);
    }

    // Agent 的主入口
    public String chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        // 1. 把用户消息加入历史
        messages.add(Map.of("role", "user", "content", userInput));
        // 2. 检查当前消息是否太长
        context.maybeCompress(messages, llm);
        // 3. 开始最多 maxRounds 轮的“模型 -> 工具 -> 模型”循环
        for (int i = 0; i < maxRounds; i++) {
            // 收集模型本轮生成的普通文本内容
            StringBuilder text = new StringBuilder();
            // 按工具 index 保存已经提前提交的执行任务。
            Map<Integer, Future<String>> futures = new LinkedHashMap<>();

            // 本轮工具执行共用一个固定线程池，最多并行 8 个工具。
            try (var pool = Executors.newFixedThreadPool(8)) {
                // 4. 调用大模型: 完整消息，包括 system prompt;工具 schema 列表;把流式返回的文本不断追加到 text
                LlmClient.Response r = llm.chat(fullMessages(), toolSchemas(), text::append,
                        // 某个流式工具参数拼完整后，立即提交到线程池执行。
                        (idx, tc) -> submit(futures, pool, idx, tc, onTool));

                // 5a. 没有要求调用任何工具
                if (r.toolCalls().isEmpty()) {
                    // 把模型回复加入消息历史。
                    messages.add(r.message());
                    // 如果有流式回调，并且文本不为空，就把完整文本传给回调
                    if (onToken != null && !text.isEmpty()) onToken.accept(text.toString());
                    // 返回模型的最终内容，结束 chat
                    return r.content();
                }

                // 5b. 模型请求调用工具的情况
                // 保存工具调用指令tool_calls
                messages.add(r.message());

                // 等待提前提交的工具；没提前提交的工具在这里兜底提交。
                List<String> results = collectResults(r.toolCalls(), futures, pool, onTool);
                // 遍历每一个工具调用，把执行结果写回消息历史
                for (int j = 0; j < r.toolCalls().size(); j++) {
                    // 使用 LinkedHashMap 是为了保持字段插入顺序，方便调试或序列化时更稳定
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "tool");
                    m.put("tool_call_id", r.toolCalls().get(j).id());
                    m.put("content", results.get(j));
                    messages.add(m);
                }
            }
            // 工具执行完之后，再检查一次消息是否过长。因为工具结果也可能很长
            context.maybeCompress(messages, llm);
        }
        return "(已达到最大工具调用轮数)";
    }

    // 把一个工具调用按 index 提交到线程池；已提交过则直接复用原 Future。
    private void submit(Map<Integer, Future<String>> futures, java.util.concurrent.ExecutorService pool, int idx, LlmClient.ToolCall tc, BiConsumer<String, Map<String, Object>> onTool) {
        // computeIfAbsent 保证同一个 index 不会因为流式回调和兜底逻辑被执行两次。
        futures.computeIfAbsent(idx, ignored -> pool.submit(() -> exec(tc, onTool)));
    }

    // 按模型返回的工具顺序收集结果，保证写回 messages 的 tool_result 顺序稳定。
    private List<String> collectResults(List<LlmClient.ToolCall> calls, Map<Integer, Future<String>> futures, java.util.concurrent.ExecutorService pool, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        // 结果列表顺序必须和 tool_calls 顺序一致。
        List<String> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            // 如果该工具没有在 SSE 读取阶段提前启动，这里按旧逻辑启动。
            submit(futures, pool, i, calls.get(i), onTool);
            // 即使后面的工具先完成，也按 index 顺序等待和写回。
            results.add(futures.get(i).get());
        }
        return results;
    }

    public void reset() {
        messages.clear();
    }

    public String runSubAgent(String task, int maxRounds) throws Exception {
        List<Tools.Tool> subTools = tools.stream()
                .filter(t -> !"Task".equals(t.name()))
                .toList();
        Agent sub = new Agent(llm, subTools, context.maxTokens, maxRounds);
        return sub.chat(task, null, null);
    }

    private List<Map<String, Object>> fullMessages() {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(Map.of("role", "system", "content", system));
        all.addAll(messages);
        return all;
    }

    private List<Map<String, Object>> toolSchemas() {
        return tools.stream().map(Tools.Tool::schema).toList();
    }

    private String exec(LlmClient.ToolCall tc, BiConsumer<String, Map<String, Object>> onTool) {
        if (onTool != null) onTool.accept(tc.name(), tc.arguments());
        Tools.Tool tool = Tools.get(tools, tc.name());
        if (tool == null) return "错误: 未知工具 '" + tc.name() + "'";
        try {
            return tool.execute(tc.arguments());
        } catch (Exception e) {
            return "错误: 执行工具 " + tc.name() + " 失败: " + e.getMessage();
        }
    }

}
