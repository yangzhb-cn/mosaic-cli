package com.yang.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析 Planner 输出的 JSON plan，并校验任务 id、依赖和环。 */
public final class PlanParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PlanParser() {
    }

    public static ExecutionPlan parse(String task, String raw) {
        try {
            JsonNode root = JSON.readTree(extractJson(raw));
            JsonNode tasksNode = root.get("tasks");
            if (tasksNode == null || !tasksNode.isArray() || tasksNode.isEmpty()) {
                throw new IllegalArgumentException("plan.tasks 必须是非空数组");
            }
            List<PlanTask> tasks = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonNode node : tasksNode) {
                String id = text(node, "id");
                if (!ids.add(id)) throw new IllegalArgumentException("重复任务 id: " + id);
                tasks.add(new PlanTask(
                        id,
                        text(node, "description"),
                        type(text(node, "type")),
                        dependencies(node.get("dependencies"))
                ));
            }
            validateDependencies(tasks);
            validateAcyclic(tasks);
            return new ExecutionPlan(task, tasks);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 plan JSON: " + e.getMessage(), e);
        }
    }

    static String extractJson(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstBrace = text.indexOf('{');
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) return text.substring(firstBrace, lastBrace + 1);
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) return text.substring(firstBrace, lastBrace + 1);
        return text;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("任务缺少字段: " + name);
        }
        return value.asText().strip();
    }

    private static TaskType type(String raw) {
        try {
            return TaskType.valueOf(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知任务类型: " + raw);
        }
    }

    private static List<String> dependencies(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("dependencies 必须是数组");
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("dependencies 只能包含非空字符串");
            out.add(item.asText().strip());
        }
        return out;
    }

    private static void validateDependencies(List<PlanTask> tasks) {
        Set<String> ids = new HashSet<>();
        for (PlanTask task : tasks) ids.add(task.id());
        for (PlanTask task : tasks) {
            for (String dep : task.dependencies()) {
                if (!ids.contains(dep)) throw new IllegalArgumentException("任务 " + task.id() + " 依赖不存在: " + dep);
                if (dep.equals(task.id())) throw new IllegalArgumentException("任务不能依赖自己: " + task.id());
            }
        }
    }

    private static void validateAcyclic(List<PlanTask> tasks) {
        Map<String, PlanTask> byId = new LinkedHashMap<>();
        for (PlanTask task : tasks) byId.put(task.id(), task);
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (PlanTask task : tasks) visit(task, byId, visiting, visited);
    }

    private static void visit(PlanTask task, Map<String, PlanTask> byId, Set<String> visiting, Set<String> visited) {
        if (visited.contains(task.id())) return;
        if (!visiting.add(task.id())) throw new IllegalArgumentException("plan 存在环形依赖: " + task.id());
        for (String dep : task.dependencies()) visit(byId.get(dep), byId, visiting, visited);
        visiting.remove(task.id());
        visited.add(task.id());
    }
}
