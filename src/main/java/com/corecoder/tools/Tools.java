package com.corecoder.tools;

import com.corecoder.Agent;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Tools {
    private static final Set<String> CHANGED = new LinkedHashSet<>();

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
        return List.of(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new GlobTool(),
                new GrepTool(),
                new AgentTool(parent)
        );
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
}
