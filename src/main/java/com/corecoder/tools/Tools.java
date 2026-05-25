package com.corecoder.tools;

import com.corecoder.Agent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Tools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CHANGED = new LinkedHashSet<>();
    private static final List<Map<String, Object>> TODOS = new ArrayList<>();

    private Tools() {
    }

    public interface Tool {
        String name();
        String description();
        Map<String, Object> parameters();
        String execute(Map<String, Object> args);

        default Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of(
                    "name", name(),
                    "description", description(),
                    "parameters", parameters()
            ));
        }
    }

    public static List<Tool> all(Agent parent) {
        List<Tool> tools = new ArrayList<>(List.of(
                new BashTool(),
                new GlobTool(),
                new GrepTool(),
                new LsTool(),
                new ReadFileTool(),
                new EditFileTool(),
                new MultiEditTool(),
                new WriteFileTool(),
                new TodoReadTool(),
                new TodoWriteTool(),
                new AgentTool(parent)
        ));
        if (parent != null && parent.imClient() != null) tools.add(new SendMessageTool(parent));
        return List.copyOf(tools);
    }

    public static Tool get(List<Tool> tools, String name) {
        for (Tool t : tools) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }

    public static Set<String> changedFiles() {
        return CHANGED;
    }

    public static void markChanged(Path path) {
        CHANGED.add(path.toString());
    }

    public static void replaceTodos(List<Map<String, Object>> todos) {
        TODOS.clear();
        TODOS.addAll(todos);
    }

    public static int todoCount() {
        return TODOS.size();
    }

    public static String todosJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(TODOS);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
