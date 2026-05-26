package com.yang.plan;

import java.util.List;

/** 表示 DAG 中的单个任务节点及其依赖、状态、结果和尝试次数。 */
public final class PlanTask {
    private final String id;
    private final String description;
    private final TaskType type;
    private final List<String> dependencies;
    private TaskStatus status = TaskStatus.PENDING;
    private String result = "";
    private String error = "";
    private int attempts;

    public PlanTask(String id, String description, TaskType type, List<String> dependencies) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public TaskType type() {
        return type;
    }

    public List<String> dependencies() {
        return dependencies;
    }

    public TaskStatus status() {
        return status;
    }

    public String result() {
        return result;
    }

    public String error() {
        return error;
    }

    public int attempts() {
        return attempts;
    }

    public boolean writeLocked() {
        return type == TaskType.FILE_WRITE || type == TaskType.COMMAND;
    }

    void markRunning() {
        status = TaskStatus.RUNNING;
    }

    void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result == null ? "" : result;
        this.error = "";
    }

    void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error == null ? "" : error;
        this.result = this.error;
    }

    void incrementAttempts() {
        attempts++;
    }
}
