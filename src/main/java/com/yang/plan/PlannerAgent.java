package com.yang.plan;

import com.yang.llm.LlmClient;
import com.yang.audit.ToolAudit;
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
    private static final String SYSTEM = """
            你是 Planner Agent，只负责为编码 CLI 生成可执行 DAG 计划。

            规则：
            - 只做只读探索和计划设计，不执行写文件、命令、编辑或子 Agent。
            - 可以使用只读/搜索工具理解项目现状。
            - 最终回复必须是严格 JSON，不要 Markdown，不要解释。
            - JSON 格式固定为：
              {"tasks":[{"id":"T1","description":"...","type":"FILE_READ","dependencies":[]}]}
            - id 使用 T1、T2、T3 这类稳定短 id。
            - type 只能是 PLANNING、FILE_READ、FILE_WRITE、COMMAND、ANALYSIS、VERIFICATION。
            - dependencies 只能引用前面或已有任务 id，表示执行前必须完成的任务。
            - 计划保持 MVP，避免不必要任务。
            """.strip();

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
        messages.add(Map.of("role", "system", "content", SYSTEM));
        messages.add(Map.of("role", "user", "content", "请为下面任务生成 DAG 执行计划：\n" + task));

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
                    return PlanParser.parse(task, response.content());
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
            }
        }
        throw new IllegalStateException("Planner 达到最大轮数仍未输出 plan JSON");
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
