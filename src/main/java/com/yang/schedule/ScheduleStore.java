package com.yang.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** JSON 持久化的后台计划任务存储。 */
public final class ScheduleStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final boolean enabled;

    public ScheduleStore(Path dataDir) {
        this(dataDir == null ? null : dataDir.resolve("schedule").resolve("tasks.json"), true);
    }

    private ScheduleStore(Path file, boolean enabled) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        this.enabled = enabled;
    }

    public static ScheduleStore disabled() {
        return new ScheduleStore(null, false);
    }

    public synchronized ScheduledTask create(String prompt, String type, String runAt, Integer intervalSeconds, String chatId) throws IOException {
        if (!enabled) throw new IllegalStateException("Schedule 未启用");
        String now = now();
        String normalizedType = normalizeType(type);
        String nextRun = nextRun(normalizedType, runAt, intervalSeconds, Instant.now());
        ScheduledTask task = new ScheduledTask(
                "task_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8),
                prompt,
                normalizedType,
                ScheduledTask.TYPE_ONCE.equals(normalizedType) ? normalizeTime(runAt) : "",
                intervalSeconds,
                ScheduledTask.STATUS_ACTIVE,
                chatId == null ? "" : chatId,
                now,
                now,
                "",
                nextRun,
                "",
                ""
        );
        List<ScheduledTask> tasks = new ArrayList<>(list());
        tasks.add(task);
        write(tasks);
        return task;
    }

    public synchronized List<ScheduledTask> list() throws IOException {
        if (!enabled || !Files.exists(file)) return List.of();
        return JSON.readValue(file.toFile(), new TypeReference<>() {});
    }

    public synchronized List<ScheduledTask> list(String status) throws IOException {
        String s = status == null ? "" : status.strip();
        return list().stream()
                .filter(t -> s.isBlank() || s.equalsIgnoreCase(t.status()))
                .sorted(Comparator.comparing(ScheduledTask::createdAt))
                .toList();
    }

    public synchronized List<ScheduledTask> dueTasks(Instant now) throws IOException {
        return list().stream()
                .filter(t -> ScheduledTask.STATUS_ACTIVE.equals(t.status()))
                .filter(t -> t.nextRun() != null && !t.nextRun().isBlank())
                .filter(t -> !Instant.parse(t.nextRun()).isAfter(now))
                .toList();
    }

    public synchronized ScheduledTask load(String id) throws IOException {
        return list().stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized boolean markRunning(String id) throws IOException {
        ScheduledTask task = load(id);
        if (task == null || !ScheduledTask.STATUS_ACTIVE.equals(task.status())) return false;
        replace(task.withStatus(ScheduledTask.STATUS_RUNNING, now()));
        return true;
    }

    public synchronized void finishRun(ScheduledTask task, boolean success, String result, String error, Instant finishedAt) throws IOException {
        String status;
        String nextRun = "";
        if (ScheduledTask.TYPE_INTERVAL.equals(task.type()) && success) {
            status = ScheduledTask.STATUS_ACTIVE;
            nextRun = finishedAt.plusSeconds(task.intervalSeconds()).toString();
        } else if (ScheduledTask.TYPE_INTERVAL.equals(task.type())) {
            status = ScheduledTask.STATUS_ERROR;
        } else {
            status = ScheduledTask.STATUS_COMPLETED;
        }
        replace(task.withRunResult(status, finishedAt.toString(), nextRun, success ? result : "", success ? "" : error, now()));
    }

    public synchronized boolean pause(String id) throws IOException {
        ScheduledTask task = load(id);
        if (task == null || !(ScheduledTask.STATUS_ACTIVE.equals(task.status()) || ScheduledTask.STATUS_ERROR.equals(task.status()))) return false;
        replace(task.withStatus(ScheduledTask.STATUS_PAUSED, now()));
        return true;
    }

    public synchronized boolean resume(String id) throws IOException {
        ScheduledTask task = load(id);
        if (task == null || !(ScheduledTask.STATUS_PAUSED.equals(task.status()) || ScheduledTask.STATUS_ERROR.equals(task.status()))) return false;
        String nextRun = ScheduledTask.TYPE_INTERVAL.equals(task.type())
                ? Instant.now().plusSeconds(task.intervalSeconds()).toString()
                : maxNow(task.runAt());
        replace(task.withNextRun(ScheduledTask.STATUS_ACTIVE, nextRun, now()));
        return true;
    }

    public synchronized boolean cancel(String id) throws IOException {
        ScheduledTask task = load(id);
        if (task == null || ScheduledTask.STATUS_CANCELED.equals(task.status())) return false;
        replace(task.withStatus(ScheduledTask.STATUS_CANCELED, now()));
        return true;
    }

    private void replace(ScheduledTask updated) throws IOException {
        List<ScheduledTask> tasks = new ArrayList<>();
        boolean found = false;
        for (ScheduledTask task : list()) {
            if (task.id().equals(updated.id())) {
                tasks.add(updated);
                found = true;
            } else {
                tasks.add(task);
            }
        }
        if (!found) tasks.add(updated);
        write(tasks);
    }

    private void write(List<ScheduledTask> tasks) throws IOException {
        Files.createDirectories(file.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), tasks);
    }

    static String normalizeType(String type) {
        String s = type == null ? "" : type.strip().toLowerCase();
        if (!ScheduledTask.TYPE_ONCE.equals(s) && !ScheduledTask.TYPE_INTERVAL.equals(s)) {
            throw new IllegalArgumentException("schedule_type 只能是 once 或 interval");
        }
        return s;
    }

    static String nextRun(String type, String runAt, Integer intervalSeconds, Instant now) {
        if (ScheduledTask.TYPE_ONCE.equals(type)) return normalizeTime(runAt);
        if (intervalSeconds == null || intervalSeconds <= 0) throw new IllegalArgumentException("interval_seconds 必须大于 0");
        return now.plusSeconds(intervalSeconds).toString();
    }

    static String normalizeTime(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("once 任务必须提供 run_at");
        String s = value.strip();
        try {
            return Instant.parse(s).toString();
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toString();
        }
    }

    private static String maxNow(String runAt) {
        Instant target = Instant.parse(normalizeTime(runAt));
        Instant now = Instant.now();
        return target.isAfter(now) ? target.toString() : now.toString();
    }

    private static String now() {
        return Instant.now().toString();
    }
}
