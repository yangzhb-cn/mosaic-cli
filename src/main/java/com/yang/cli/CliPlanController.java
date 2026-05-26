package com.yang.cli;

import com.yang.llm.LlmClient;
import com.yang.plan.ExecutionPlan;
import com.yang.plan.PlanPrinter;
import com.yang.plan.PlanRunResult;
import com.yang.plan.PlanRunner;
import com.yang.plan.PlanSession;
import com.yang.plan.PlannerAgent;
import com.yang.plan.TaskStatus;
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
    private final LlmClient llm;

    public CliPlanController(PlannerAgent plannerAgent, PlanRunner planRunner, List<Map<String, Object>> messages, Runnable onMessagesChanged) {
        this(plannerAgent, planRunner, messages, onMessagesChanged, null);
    }

    public CliPlanController(PlannerAgent plannerAgent, PlanRunner planRunner, List<Map<String, Object>> messages, Runnable onMessagesChanged, LlmClient llm) {
        this.plannerAgent = plannerAgent;
        this.planRunner = planRunner;
        this.messages = messages;
        this.onMessagesChanged = onMessagesChanged == null ? () -> {} : onMessagesChanged;
        this.llm = llm;
    }

    public String chatCli(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool, ChatHandler fallback) throws Exception {
        if (session.awaitingTask()) return createPlan(userInput, onTool);
        if (session.state() == PlanSession.State.EXECUTING) return "⏳ 当前计划正在执行中。";
        if (session.ready()) return "🧭 当前已有计划，请使用 /act 执行，或使用 /plan 重新规划，/cancel 取消。";
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
        return createPlan(task, null);
    }

    public String createPlan(String task, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        ExecutionPlan plan = plannerAgent.plan(task, onTool);
        session.setPlan(plan);
        String response = PlanPrinter.table(plan) + "\n\n🚀 /act 执行，🧭 /plan 重新规划，❌ /cancel 取消。";
        messages.add(Map.of("role", "user", "content", task));
        messages.add(Map.of("role", "assistant", "content", response));
        onMessagesChanged.run();
        return response;
    }

    public String act() throws Exception {
        return act(null);
    }

    public String act(Consumer<String> onProgress) throws Exception {
        if (!session.ready()) return "📭 当前没有可执行计划。";
        session.executing();
        try {
            ExecutionPlan plan = session.plan();
            PlanRunResult result = planRunner.run(plan, onProgress);
            StringBuilder out = new StringBuilder();
            out.append(result.success() ? "✅ 计划执行完成。" : "🛑 计划执行停止: " + result.failure());
            out.append("\n\n📌 执行总结:\n").append(summaryWithLlm(plan, result));
            out.append("\n\n").append(PlanPrinter.table(plan));
            out.append("\n\n📋 任务结果:\n");
            for (var task : plan.tasks()) {
                out.append("- ").append(task.id()).append(" ").append(task.status()).append(": ")
                        .append(taskSummary(task))
                        .append('\n');
            }
            if (!Tools.changedFiles().isEmpty()) {
                out.append("\n\n📝 Changed files:\n");
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
        return "❌ 已取消当前计划。";
    }

    private static String taskSummary(com.yang.plan.PlanTask task) {
        String text = switch (task.status()) {
            case COMPLETED -> task.result();
            case FAILED -> task.error();
            case PENDING -> "未执行";
            case RUNNING -> "仍在执行";
        };
        text = text == null || text.isBlank() ? "无输出" : text.replaceAll("\\s+", " ").strip();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static String summary(ExecutionPlan plan, PlanRunResult result) {
        long completed = plan.tasks().stream().filter(t -> t.status() == TaskStatus.COMPLETED).count();
        long failed = plan.tasks().stream().filter(t -> t.status() == TaskStatus.FAILED).count();
        long pending = plan.tasks().stream().filter(t -> t.status() == TaskStatus.PENDING).count();
        StringBuilder out = new StringBuilder();
        out.append("- 任务：完成 ").append(completed)
                .append("，失败 ").append(failed)
                .append("，未执行 ").append(pending)
                .append('\n');
        if (!result.success() && !result.failure().isBlank()) {
            out.append("- 停止原因：").append(result.failure()).append('\n');
        }
        String keyResult = keyResult(plan);
        if (!keyResult.isBlank()) {
            out.append("- 结果：").append(keyResult).append('\n');
        }
        if (!Tools.changedFiles().isEmpty()) {
            out.append("- 修改文件：").append(Tools.changedFiles().size()).append(" 个\n");
        }
        return out.toString().stripTrailing();
    }

    private String summaryWithLlm(ExecutionPlan plan, PlanRunResult result) {
        if (llm == null) return summary(plan, result);
        try {
            String content = llm.chat(summaryMessages(plan, result), List.of(), null, null).content();
            return content == null || content.isBlank() ? summary(plan, result) : content.strip();
        } catch (Exception ignored) {
            return summary(plan, result);
        }
    }

    private static List<Map<String, Object>> summaryMessages(ExecutionPlan plan, PlanRunResult result) {
        return List.of(
                Map.of("role", "system", "content", """
                        你是 MosaicCoder 的执行总结器。只根据用户计划执行结果总结，不编造未提供的信息。
                        用简短中文输出 2-4 条要点，覆盖完成情况、关键结果、失败/未执行任务和修改文件。
                        不要输出 Markdown 标题。
                        """),
                Map.of("role", "user", "content", summaryInput(plan, result))
        );
    }

    private static String summaryInput(ExecutionPlan plan, PlanRunResult result) {
        StringBuilder out = new StringBuilder();
        out.append("原始目标：").append(plan.task()).append('\n');
        out.append("执行状态：").append(result.success() ? "成功" : "停止").append('\n');
        if (!result.success() && !result.failure().isBlank()) out.append("失败原因：").append(result.failure()).append('\n');
        out.append("任务结果：\n");
        for (var task : plan.tasks()) {
            out.append("- ").append(task.id()).append(' ')
                    .append(task.type()).append(' ')
                    .append(task.status()).append(": ")
                    .append(taskSummary(task))
                    .append('\n');
        }
        if (!Tools.changedFiles().isEmpty()) {
            out.append("修改文件：\n");
            Tools.changedFiles().stream().sorted().forEach(f -> out.append("- ").append(f).append('\n'));
        }
        return out.toString();
    }

    private static String keyResult(ExecutionPlan plan) {
        for (int i = plan.tasks().size() - 1; i >= 0; i--) {
            var task = plan.tasks().get(i);
            if (task.status() == TaskStatus.COMPLETED && task.result() != null && !task.result().isBlank()) {
                return taskSummary(task);
            }
        }
        return "";
    }
}
