package com.yang.plan;

import com.yang.agent.Agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 按 DAG 依赖并行执行计划任务，并处理重试、写任务串行和失败停止。 */
public final class PlanRunner {
    private static final int MAX_ATTEMPTS = 3;

    /** 执行单个 DAG task 的回调，生产环境由子 Agent 实现，测试可替换。 */
    @FunctionalInterface
    public interface TaskExecutor {
        String execute(PlanTask task, ExecutionPlan plan) throws Exception;
    }

    private final TaskExecutor executor;
    private final int parallelism;
    private final Object writeLock = new Object();

    public PlanRunner(Agent agent) {
        this((task, plan) -> agent.runSubAgent(prompt(task, plan), 20), 4);
    }

    public PlanRunner(TaskExecutor executor) {
        this(executor, 4);
    }

    public PlanRunner(TaskExecutor executor, int parallelism) {
        this.executor = executor;
        this.parallelism = parallelism;
    }

    public PlanRunResult run(ExecutionPlan plan) throws Exception {
        if (plan == null || plan.tasks().isEmpty()) return new PlanRunResult(false, "当前没有可执行计划");

        String failure = null;
        Map<PlanTask, Future<TaskResult>> running = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(parallelism)) {
            boolean stopScheduling = false;
            while (true) {
                if (!stopScheduling) {
                    for (PlanTask task : plan.readyTasks()) {
                        task.markRunning();
                        running.put(task, pool.submit(() -> runWithRetry(task, plan)));
                    }
                }
                if (running.isEmpty()) break;

                List<Map.Entry<PlanTask, Future<TaskResult>>> done = waitDone(running);
                for (Map.Entry<PlanTask, Future<TaskResult>> entry : done) {
                    running.remove(entry.getKey());
                    TaskResult result = get(entry.getValue());
                    if (result.success()) {
                        entry.getKey().markCompleted(result.output());
                    } else {
                        entry.getKey().markFailed(result.output());
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

    private TaskResult runWithRetry(PlanTask task, ExecutionPlan plan) {
        String last = "";
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            task.incrementAttempts();
            try {
                String result = execute(task, plan);
                if (result == null || !result.startsWith("错误:")) return new TaskResult(true, result == null ? "" : result);
                last = result;
            } catch (Exception e) {
                last = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }
        return new TaskResult(false, last);
    }

    private String execute(PlanTask task, ExecutionPlan plan) throws Exception {
        if (!task.writeLocked()) return executor.execute(task, plan);
        synchronized (writeLock) {
            return executor.execute(task, plan);
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

    private static String prompt(PlanTask task, ExecutionPlan plan) {
        StringBuilder out = new StringBuilder();
        out.append("你是执行子 Agent。只完成当前 DAG task，不要扩展无关范围。\n");
        out.append("原始目标：").append(plan.task()).append("\n");
        out.append("当前任务：").append(task.id()).append(" [").append(task.type()).append("] ").append(task.description()).append("\n");
        if (!task.dependencies().isEmpty()) {
            out.append("依赖结果：\n");
            for (String dep : task.dependencies()) {
                PlanTask dependency = plan.task(dep);
                out.append("- ").append(dep).append(": ")
                        .append(dependency == null ? "" : dependency.result())
                        .append('\n');
            }
        }
        out.append("完成后用简短中文总结结果。");
        return out.toString();
    }

    /** 单个 task 执行后的成功状态和输出。 */
    private record TaskResult(boolean success, String output) {
    }
}
