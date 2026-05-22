package com.corecoder;

import com.corecoder.tools.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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

            // 4. 调用大模型: 完整消息，包括 system prompt;工具 schema 列表;把流式返回的文本不断追加到 text
            LlmClient.Response r = llm.chat(fullMessages(), toolSchemas(), text::append);
            
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
            // 包含工具调用指令tool_calls
            messages.add(r.message());

            // 如果只有一个工具调用,直接执行 exec(...);如果有多个工具调用：并行执行 execParallel(...)
            List<String> results = r.toolCalls().size() == 1 ? List.of(exec(r.toolCalls().getFirst(), onTool)) : execParallel(r.toolCalls(), onTool);
            // 遍历每一个工具调用，把执行结果写回消息历史
            for (int j = 0; j < r.toolCalls().size(); j++) {
                // 使用 LinkedHashMap 是为了保持字段插入顺序，方便调试或序列化时更稳定
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "tool");
                m.put("tool_call_id", r.toolCalls().get(j).id());
                m.put("content", results.get(j));
                messages.add(m);
            }
            // 工具执行完之后，再检查一次消息是否过长。因为工具结果也可能很长
            context.maybeCompress(messages, llm);
        }
        return "(已达到最大工具调用轮数)";
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

    private List<String> execParallel(List<LlmClient.ToolCall> calls, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        try (var pool = Executors.newFixedThreadPool(Math.min(8, calls.size()))) {
            var futures = calls.stream().map(tc -> pool.submit(() -> exec(tc, onTool))).toList();
            List<String> results = new ArrayList<>();
            for (var f : futures) results.add(f.get());
            return results;
        }
    }
}
