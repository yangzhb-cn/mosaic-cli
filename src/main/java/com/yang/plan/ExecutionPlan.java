package com.yang.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保存一次规划任务的 DAG 节点列表，并提供依赖就绪状态查询。 */
public final class ExecutionPlan {
    private final String task;
    private final List<PlanTask> tasks;
    private final Map<String, PlanTask> byId;

    public ExecutionPlan(String task, List<PlanTask> tasks) {
        this.task = task == null ? "" : task;
        this.tasks = new ArrayList<>(tasks == null ? List.of() : tasks);
        this.byId = new LinkedHashMap<>();
        for (PlanTask planTask : this.tasks) {
            byId.put(planTask.id(), planTask);
        }
    }

    public String task() {
        return task;
    }

    public List<PlanTask> tasks() {
        return tasks;
    }

    public PlanTask task(String id) {
        return byId.get(id);
    }

    public List<PlanTask> readyTasks() {
        List<PlanTask> ready = new ArrayList<>();
        for (PlanTask task : tasks) {
            if (task.status() != TaskStatus.PENDING) continue;
            boolean dependenciesDone = true;
            for (String dep : task.dependencies()) {
                PlanTask dependency = byId.get(dep);
                if (dependency == null || dependency.status() != TaskStatus.COMPLETED) {
                    dependenciesDone = false;
                    break;
                }
            }
            if (dependenciesDone) ready.add(task);
        }
        return ready;
    }

    public boolean hasPending() {
        for (PlanTask task : tasks) {
            if (task.status() == TaskStatus.PENDING) return true;
        }
        return false;
    }

    public boolean allCompleted() {
        return !tasks.isEmpty() && tasks.stream().allMatch(t -> t.status() == TaskStatus.COMPLETED);
    }
}
