package com.yang.cli;

import com.yang.plan.ExecutionPlan;
import com.yang.plan.PlanPrinter;
import com.yang.plan.PlanRunResult;
import com.yang.plan.PlanRunner;
import com.yang.plan.PlanSession;
import com.yang.plan.PlannerAgent;
import com.yang.tool.Tools;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 管理 CLI 规划流程，把 /plan 输入、DAG 生成、执行和取消串起来。 */
public final class CliPlanController {
    /** 普通聊天入口回调，供 Plan 流程没有接管输入时继续走主 Agent。 */
    @FunctionalInterface
    public interface ChatHandler {
        String chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool) throws Exception;
    }

    private final PlanSession session = new PlanSession();
    private final PlannerAgent plannerAgent;
    private final PlanRunner planRunner;
    private final List<Map<String, Object>> messages;
    private final Runnable onMessagesChanged;

    public CliPlanController(PlannerAgent plannerAgent, PlanRunner planRunner, List<Map<String, Object>> messages, Runnable onMessagesChanged) {
        this.plannerAgent = plannerAgent;
        this.planRunner = planRunner;
        this.messages = messages;
        this.onMessagesChanged = onMessagesChanged == null ? () -> {} : onMessagesChanged;
    }

    public String chatCli(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool, ChatHandler fallback) throws Exception {
        if (session.awaitingTask()) return createPlan(userInput);
        if (session.state() == PlanSession.State.EXECUTING) return "当前计划正在执行中。";
        if (session.ready()) return "当前已有计划，请使用 /act 执行，或使用 /plan 重新规划，/cancel 取消。";
        return fallback.chat(userInput, onToken, onTool);
    }

    public boolean isActive() {
        return session.isActive();
    }

    public void enter() {
        session.awaitTask();
    }

    public void clear() {
        session.clear();
    }

    public PlanSession session() {
        return session;
    }

    public String createPlan(String task) throws Exception {
        ExecutionPlan plan = plannerAgent.plan(task);
        session.setPlan(plan);
        String response = PlanPrinter.table(plan) + "\n\n/act 执行，/plan 重新规划，/cancel 取消。";
        messages.add(Map.of("role", "user", "content", task));
        messages.add(Map.of("role", "assistant", "content", response));
        onMessagesChanged.run();
        return response;
    }

    public String act() throws Exception {
        if (!session.ready()) return "当前没有可执行计划。";
        session.executing();
        try {
            PlanRunResult result = planRunner.run(session.plan());
            StringBuilder out = new StringBuilder();
            out.append(result.success() ? "计划执行完成。" : "计划执行停止: " + result.failure());
            out.append("\n\n").append(PlanPrinter.table(session.plan()));
            if (!Tools.changedFiles().isEmpty()) {
                out.append("\n\nChanged files:\n");
                Tools.changedFiles().stream().sorted().forEach(f -> out.append("- ").append(f).append('\n'));
            }
            String response = out.toString().stripTrailing();
            messages.add(Map.of("role", "assistant", "content", response));
            onMessagesChanged.run();
            return response;
        } finally {
            session.finishExecuting();
        }
    }

    public String cancel() {
        session.clear();
        return "已取消当前计划。";
    }
}
