package com.yang.plan;

import com.yang.llm.LlmClient;
import com.yang.audit.ToolAudit;
import com.yang.prompt.Prompt;
import com.yang.tool.GlobTool;
import com.yang.tool.GrepTool;
import com.yang.tool.LsTool;
import com.yang.tool.ReadFileTool;
import com.yang.tool.ToolExecutor;
import com.yang.tool.Tools;
import com.yang.tool.WebFetchTool;
import com.yang.tool.WebSearchTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 独立规划 Agent，只使用只读/搜索工具生成严格 JSON DAG。 */
public final class PlannerAgent {
    private static final int MAX_ROUNDS = 12;

    private final LlmClient llm;
    private final ToolExecutor toolExecutor;
    private final List<Tools.Tool> tools;

    public PlannerAgent(LlmClient llm, ToolAudit audit) {
        this.llm = llm;
        this.tools = List.of(
                new ReadFileTool(),
                new LsTool(),
                new GlobTool(),
                new GrepTool(),
                new WebFetchTool(),
                new WebSearchTool()
        );
        this.toolExecutor = new ToolExecutor(tools, audit);
    }

    public ExecutionPlan plan(String task) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", Prompt.plannerPrompt()));
        messages.add(Map.of("role", "user", "content", "请为下面任务生成 DAG 执行计划：\n" + task));

        String lastError = "";
        for (int i = 0; i < MAX_ROUNDS; i++) {
            Map<Integer, Future<String>> futures = new LinkedHashMap<>();
            try (var pool = toolPool()) {
                LlmClient.Response response = llm.chat(
                        messages,
                        toolExecutor.schemas(),
                        null,
                        (idx, tc) -> toolExecutor.submit(futures, pool, idx, tc, null)
                );
                if (response.toolCalls().isEmpty()) {
                    try {
                        return PlanParser.parse(task, response.content());
                    } catch (IllegalArgumentException e) {
                        lastError = e.getMessage();
                        messages.add(response.message());
                        messages.add(Map.of("role", "user", "content",
                                "上一次输出不是合法 plan JSON: " + lastError
                                        + "\n请只输出严格 JSON，不要 Markdown，不要解释，格式为 {\"tasks\":[...]}。"));
                        continue;
                    }
                }

                messages.add(response.message());
                List<String> results = toolExecutor.collectResults(response.toolCalls(), futures, pool, null);
                for (int j = 0; j < response.toolCalls().size(); j++) {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("role", "tool");
                    message.put("tool_call_id", response.toolCalls().get(j).id());
                    message.put("content", results.get(j));
                    messages.add(message);
                }
                messages.add(Map.of("role", "user", "content",
                        "如果已经掌握足够信息，下一轮请直接输出严格 JSON plan；只有确实缺少必要信息时才继续调用只读工具。"));
            }
        }
        throw new IllegalStateException("Planner 达到最大轮数仍未输出 plan JSON" + (lastError.isBlank() ? "" : ": " + lastError));
    }

    public List<String> toolNames() {
        return tools.stream().map(Tools.Tool::name).toList();
    }

    private static ExecutorService toolPool() {
        return new ThreadPoolExecutor(
                6,
                6,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
