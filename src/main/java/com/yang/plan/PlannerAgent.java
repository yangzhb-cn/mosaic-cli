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
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** 独立规划 Agent，只使用只读/搜索工具生成严格 JSON DAG。 */
public final class PlannerAgent {
    private static final int MAX_ROUNDS = 5;

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
        return plan(task, null);
    }

    public ExecutionPlan plan(String task, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", Prompt.plannerPrompt()));
        messages.add(Map.of("role", "user", "content",
                "当前日期时间：" + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        + "\n当前工作目录：" + Path.of("").toAbsolutePath()
                        + "\n请为下面任务生成 DAG 执行计划：\n" + task));

        String lastError = "";
        for (int i = 0; i < MAX_ROUNDS; i++) {
            Map<Integer, Future<String>> futures = new LinkedHashMap<>();
            try (var pool = toolPool()) {
                LlmClient.Response response = llm.chat(
                        messages,
                        toolExecutor.schemas(),
                        null,
                        (idx, tc) -> toolExecutor.submit(futures, pool, idx, tc, onTool)
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
                List<String> results = toolExecutor.collectResults(response.toolCalls(), futures, pool, onTool);
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
        return fallbackPlan(task, lastError);
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

    private static ExecutionPlan fallbackPlan(String task, String reason) {
        String suffix = reason == null || reason.isBlank() ? "" : "（Planner 未生成合法 JSON，使用保底计划: " + reason + "）";
        return new ExecutionPlan(task, List.of(
                new PlanTask("T1", "保底计划：理解用户目标并梳理执行要求。" + suffix, TaskType.PLANNING, List.of()),
                new PlanTask("T2", "按用户要求完成任务：" + task, TaskType.FILE_WRITE, List.of("T1")),
                new PlanTask("T3", "检查输出是否满足用户要求，并给出验收结果。", TaskType.VERIFICATION, List.of("T2"))
        ));
    }
}
