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
    final ContextManager context;
    final List<Map<String, Object>> messages = new ArrayList<>();
    private final int maxRounds;
    private final String system;

    public Agent(LlmClient llm, int maxContextTokens) {
        this.llm = llm;
        this.context = new ContextManager(maxContextTokens);
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

    public String chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        messages.add(Map.of("role", "user", "content", userInput));
        context.maybeCompress(messages, llm);
        for (int i = 0; i < maxRounds; i++) {
            LlmClient.Response r = llm.chat(fullMessages(), toolSchemas(), onToken);
            if (r.toolCalls().isEmpty()) {
                messages.add(r.message());
                return r.content();
            }
            messages.add(r.message());
            List<String> results = r.toolCalls().size() == 1 ? List.of(exec(r.toolCalls().getFirst(), onTool)) : execParallel(r.toolCalls(), onTool);
            for (int j = 0; j < r.toolCalls().size(); j++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "tool");
                m.put("tool_call_id", r.toolCalls().get(j).id());
                m.put("content", results.get(j));
                messages.add(m);
            }
            context.maybeCompress(messages, llm);
        }
        return "(reached maximum tool-call rounds)";
    }

    public void reset() {
        messages.clear();
    }

    public String runSubAgent(String task, int maxRounds) throws Exception {
        List<Tools.Tool> subTools = tools.stream()
                .filter(t -> !"agent".equals(t.name()))
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
        if (tool == null) return "Error: unknown tool '" + tc.name() + "'";
        try {
            return tool.execute(tc.arguments());
        } catch (Exception e) {
            return "Error executing " + tc.name() + ": " + e.getMessage();
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
