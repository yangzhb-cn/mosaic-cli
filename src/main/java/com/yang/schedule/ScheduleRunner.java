package com.yang.schedule;

import com.yang.agent.Agent;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** 后台轮询并执行到期计划任务。 */
public final class ScheduleRunner implements AutoCloseable {
    private static final int TASK_MAX_ROUNDS = 12;

    private final ScheduleStore store;
    private final Agent agent;
    private final int intervalSeconds;
    private final ScheduledExecutorService executor;

    public ScheduleRunner(ScheduleStore store, Agent agent, int intervalSeconds) {
        this.store = store;
        this.agent = agent;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.executor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::runDueTasksQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public void runDueTasks() throws Exception {
        for (ScheduledTask task : store.dueTasks(Instant.now())) {
            if (!store.markRunning(task.id())) continue;
            execute(task);
        }
    }

    private void runDueTasksQuietly() {
        try {
            runDueTasks();
        } catch (Exception ignored) {
        }
    }

    private void execute(ScheduledTask task) throws Exception {
        String previousChatId = agent.currentImChatId();
        boolean success = false;
        String result = "";
        String error = "";
        agent.recordEvent("schedule_run_started", java.util.Map.of("task_id", task.id(), "prompt", task.prompt()));
        try {
            if (task.chatId() != null && !task.chatId().isBlank()) agent.setCurrentImChatId(task.chatId());
            result = agent.runSubAgent(prompt(task), TASK_MAX_ROUNDS);
            success = result == null || !result.startsWith("错误:");
            if (!success) error = result;
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            agent.setCurrentImChatId(previousChatId);
        }

        Instant finishedAt = Instant.now();
        store.finishRun(task, success, result == null ? "" : result, error, finishedAt);
        agent.recordEvent("schedule_run_finished", java.util.Map.of(
                "task_id", task.id(),
                "success", success,
                "result", result == null ? "" : result,
                "error", error == null ? "" : error
        ));
        notifyIfNeeded(task, success ? result : "错误: " + error);
    }

    private void notifyIfNeeded(ScheduledTask task, String text) {
        if (agent.imClient() == null || task.chatId() == null || task.chatId().isBlank()) return;
        try {
            agent.imClient().send(task.chatId(), text == null || text.isBlank() ? "计划任务已执行。" : text);
        } catch (Exception ignored) {
        }
    }

    private static String prompt(ScheduledTask task) {
        return """
                你正在执行一个后台计划任务。只完成这个任务，不要修改主会话。
                如果需要通知用户，可以直接给出最终结果；系统会在 IM 任务中把结果发送给用户。

                任务内容：
                %s
                """.formatted(task.prompt());
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "schedule-runner");
            t.setDaemon(true);
            return t;
        };
    }
}
