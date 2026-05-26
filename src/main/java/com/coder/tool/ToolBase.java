package com.coder.tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 减少每个具体工具重复写参数解析、类型转换、路径处理这些通用逻辑
public abstract class ToolBase implements Tools.Tool {
    // 统一生成工具参数 schema
    protected Map<String, Object> params(Map<String, Object> props, String... required) {
        return Map.of(
                "type", "object",
                // 表示不允许额外字段
                "additionalProperties", false,
                // 参数定义
                "properties", props,
                // 必填字段列表
                "required", List.of(required)
        );
    }

    // 定义一个字段的 schema
    // prop("string", "文件路径")
    protected Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    // 定义数组属性
    protected Map<String, Object> arrayProp(String description, Map<String, Object> items) {
        return Map.of("type", "array", "description", description, "items", items);
    }

    // 从参数 Map 中取字符串
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
