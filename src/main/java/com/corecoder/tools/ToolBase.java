package com.corecoder.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public abstract class ToolBase implements Tools.Tool {
    protected Map<String, Object> params(Map<String, Object> props, String... required) {
        return Map.of("type", "object", "properties", props, "required", List.of(required));
    }

    protected Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
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

    protected Path path(String raw) {
        if (raw.startsWith("~")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }
}
