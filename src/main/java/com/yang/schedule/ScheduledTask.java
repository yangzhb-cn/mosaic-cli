package com.yang.schedule;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 持久化的后台计划任务，独立于当前对话 Todo。 */
public record ScheduledTask(
        @JsonProperty("id") String id,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("type") String type,
        @JsonProperty("run_at") String runAt,
        @JsonProperty("interval_seconds") Integer intervalSeconds,
        @JsonProperty("status") String status,
        @JsonProperty("chat_id") String chatId,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("last_run") String lastRun,
        @JsonProperty("next_run") String nextRun,
        @JsonProperty("last_result") String lastResult,
        @JsonProperty("last_error") String lastError
) {
    public static final String TYPE_ONCE = "once";
    public static final String TYPE_INTERVAL = "interval";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELED = "canceled";
    public static final String STATUS_ERROR = "error";

    public ScheduledTask withStatus(String status, String updatedAt) {
        return new ScheduledTask(id, prompt, type, runAt, intervalSeconds, status, chatId, createdAt, updatedAt, lastRun, nextRun, lastResult, lastError);
    }

    public ScheduledTask withNextRun(String status, String nextRun, String updatedAt) {
        return new ScheduledTask(id, prompt, type, runAt, intervalSeconds, status, chatId, createdAt, updatedAt, lastRun, nextRun, lastResult, lastError);
    }

    public ScheduledTask withRunResult(String status, String lastRun, String nextRun, String result, String error, String updatedAt) {
        return new ScheduledTask(id, prompt, type, runAt, intervalSeconds, status, chatId, createdAt, updatedAt, lastRun, nextRun, result == null ? "" : result, error == null ? "" : error);
    }
}
