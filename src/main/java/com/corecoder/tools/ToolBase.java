package com.corecoder.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class ToolBase implements Tools.Tool {
    protected Map<String, Object> params(Map<String, Object> props, String... required) {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", props,
                "required", List.of(required)
        );
    }

    protected Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    protected Map<String, Object> arrayProp(String description, Map<String, Object> items) {
        return Map.of("type", "array", "description", description, "items", items);
    }

    protected String str(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v == null ? def : String.valueOf(v);
    }

    protected int integer(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return def;
        }
        return Integer.parseInt(String.valueOf(v));
    }

    protected List<String> list(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : raw) out.add(String.valueOf(item));
        return out;
    }

    protected List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> converted = new java.util.LinkedHashMap<>();
                map.forEach((k, v) -> converted.put(String.valueOf(k), v));
                out.add(converted);
            }
        }
        return out;
    }

    protected Path path(String raw) {
        if (raw.startsWith("~")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }
}
