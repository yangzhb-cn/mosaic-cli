package com.yang.plan;

import com.yang.agent.Agent;
import com.yang.prompt.Prompt;
import com.yang.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** 按 DAG 依赖并行执行计划任务，并处理重试、写任务串行和失败停止。 */
public final class PlanRunner {
    private static final int MAX_ATTEMPTS = 3;
    private static final int SUB_AGENT_MAX_ROUNDS = 8;

    /** 执行单个 DAG task 的回调，生产环境由子 Agent 实现，测试可替换。 */
    @FunctionalInterface
    public interface TaskExecutor {
        String execute(PlanTask task, ExecutionPlan plan, Consumer<String> onProgress) throws Exception;
    }

    private final TaskExecutor executor;
    private final int parallelism;
    private final Object writeLock = new Object();

    public PlanRunner(Agent agent) {
        this((task, plan, onProgress) -> agent.runSubAgent(prompt(task, plan), SUB_AGENT_MAX_ROUNDS, subAgentToolReporter(task, onProgress)), 4);
    }

    public PlanRunner(TaskExecutor executor) {
        this(executor, 4);
    }

    public PlanRunner(TaskExecutor executor, int parallelism) {
        this.executor = executor;
        this.parallelism = parallelism;
    }

    public PlanRunResult run(ExecutionPlan plan) throws Exception {
        return run(plan, null);
    }

    public PlanRunResult run(ExecutionPlan plan, Consumer<String> onProgress) throws Exception {
        if (plan == null || plan.tasks().isEmpty()) return new PlanRunResult(false, "当前没有可执行计划");

        String failure = null;
        Map<PlanTask, Future<TaskResult>> running = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(parallelism)) {
            boolean stopScheduling = false;
            while (true) {
                if (!stopScheduling) {
                    for (PlanTask task : plan.readyTasks()) {
                        task.markRunning();
                        progress(onProgress, "▶ " + taskLabel(task));
                        running.put(task, pool.submit(() -> runWithRetry(task, plan, onProgress)));
                    }
                }
                if (running.isEmpty()) break;

                List<Map.Entry<PlanTask, Future<TaskResult>>> done = waitDone(running);
                for (Map.Entry<PlanTask, Future<TaskResult>> entry : done) {
                    running.remove(entry.getKey());
                    TaskResult result = get(entry.getValue());
                    if (result.success()) {
                        entry.getKey().markCompleted(result.output());
                        progress(onProgress, "✅ " + taskLabel(entry.getKey()) + " 完成");
                    } else {
                        entry.getKey().markFailed(result.output());
                        progress(onProgress, "❌ " + taskLabel(entry.getKey()) + " 失败: " + brief(result.output()));
                        if (failure == null) failure = entry.getKey().id() + " 失败: " + result.output();
                        stopScheduling = true;
                    }
                }
            }
        }
        if (failure != null) return new PlanRunResult(false, failure);
        if (plan.allCompleted()) return new PlanRunResult(true, "");
        if (plan.hasPending()) return new PlanRunResult(false, "存在未执行任务，请检查依赖或失败任务");
        return new PlanRunResult(true, "");
    }

    private TaskResult runWithRetry(PlanTask task, ExecutionPlan plan, Consumer<String> onProgress) {
        String last = "";
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            if (i > 0) progress(onProgress, "🔁 " + taskLabel(task) + " 重试 " + (i + 1) + "/" + MAX_ATTEMPTS);
            task.incrementAttempts();
            try {
                String result = execute(task, plan, onProgress);
                if (result == null || !result.startsWith("错误:")) return new TaskResult(true, result == null ? "" : result);
                last = result;
            } catch (Exception e) {
                last = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }
        return new TaskResult(false, last);
    }

    private String execute(PlanTask task, ExecutionPlan plan, Consumer<String> onProgress) throws Exception {
        if (!task.writeLocked()) return executor.execute(task, plan, onProgress);
        synchronized (writeLock) {
            return executor.execute(task, plan, onProgress);
        }
    }

    private static List<Map.Entry<PlanTask, Future<TaskResult>>> waitDone(Map<PlanTask, Future<TaskResult>> running) throws InterruptedException {
        while (true) {
            List<Map.Entry<PlanTask, Future<TaskResult>>> done = new ArrayList<>();
            for (Map.Entry<PlanTask, Future<TaskResult>> entry : running.entrySet()) {
                if (entry.getValue().isDone()) done.add(entry);
            }
            if (!done.isEmpty()) return done;
            Thread.sleep(10);
        }
    }

    private static TaskResult get(Future<TaskResult> future) throws ExecutionException, InterruptedException {
        return future.get();
    }

    private static void progress(Consumer<String> onProgress, String text) {
        if (onProgress == null) return;
        synchronized (onProgress) {
            onProgress.accept(text);
        }
    }

    private static String taskLabel(PlanTask task) {
        String description = brief(task.description());
        return description.isBlank()
                ? task.id() + " " + task.type()
                : task.id() + " " + task.type() + " " + description;
    }

    private static String brief(String text) {
        String s = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    private static ToolExecutor.ToolObserver subAgentToolReporter(PlanTask task, Consumer<String> onProgress) {
        return new ToolExecutor.ToolObserver() {
            @Override
            public void accept(String name, Map<String, Object> args) {
                progress(onProgress, "🔧 SubAgent(" + task.id() + ")." + name + " start(" + briefArgs(args) + ")");
            }

            @Override
            public void finished(String name, Map<String, Object> args, boolean success, long elapsedNanos, String result) {
                String status = success ? "✅" : "❌";
                String outcome = success ? "done" : "fail";
                progress(onProgress, status + " SubAgent(" + task.id() + ")." + name + " " + outcome + " "
                        + elapsed(elapsedNanos) + ", " + (success ? size(result) : brief(result)));
            }
        };
    }

    private static String briefArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        String s = args.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    private static String elapsed(long elapsedNanos) {
        return Math.max(0, Math.round(elapsedNanos / 1_000_000d)) + "ms";
    }

    private static String size(String text) {
        int bytes = (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes < 1024) return bytes + "B";
        return String.format(java.util.Locale.ROOT, "%.1fKB", bytes / 1024d);
    }

    private static String prompt(PlanTask task, ExecutionPlan plan) {
        return Prompt.subAgentPrompt(
                plan.task(),
                task.id(),
                task.type().name(),
                task.description(),
                dependencies(task, plan)
        );
    }

    private static String dependencies(PlanTask task, ExecutionPlan plan) {
        if (task.dependencies().isEmpty()) return "无";
        StringBuilder out = new StringBuilder();
        for (String dep : task.dependencies()) {
            PlanTask dependency = plan.task(dep);
            out.append("- ").append(dep).append(": ")
                    .append(dependency == null ? "" : dependency.result())
                    .append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** 单个 task 执行后的成功状态和输出。 */
    private record TaskResult(boolean success, String output) {
    }
}
