package com.yang.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 保存单个 Agent 会话里的工具状态。 */
public final class ToolState {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Set<String> changedFiles = new LinkedHashSet<>();
    private final List<Map<String, Object>> todos = new ArrayList<>();

    public Set<String> changedFiles() {
        return changedFiles;
    }

    public void markChanged(Path path) {
        changedFiles.add(path.toAbsolutePath().normalize().toString());
    }

    public void replaceTodos(List<Map<String, Object>> nextTodos) {
        todos.clear();
        todos.addAll(nextTodos);
    }

    public int todoCount() {
        return todos.size();
    }

    public String todosJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(todos);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    public void clear() {
        changedFiles.clear();
        todos.clear();
    }
}
