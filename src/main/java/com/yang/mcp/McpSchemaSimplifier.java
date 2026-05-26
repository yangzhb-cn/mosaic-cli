package com.yang.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 MCP 返回的参数 schema 简化为 LLM 工具调用可接受的 JSON Schema。 */
final class McpSchemaSimplifier {
    private McpSchemaSimplifier() {
    }

    static Map<String, Object> simplify(Map<String, Object> schema) {
        return asObject(node(schema, schema, 0));
    }

    private static Object node(Object raw, Map<String, Object> root, int depth) {
        if (depth > 8) return Map.of("type", "object");
        if (!(raw instanceof Map<?, ?> map)) return raw;

        Map<String, Object> in = toStringMap(map);
        if (in.containsKey("$ref")) {
            Object resolved = ref(String.valueOf(in.get("$ref")), root);
            if (resolved != null) return mergeDescription(in, node(resolved, root, depth + 1));
            return fallback(in, "object");
        }
        for (String key : List.of("oneOf", "anyOf", "allOf")) {
            Object picked = pick(in.get(key));
            if (picked != null) return mergeDescription(in, node(picked, root, depth + 1));
        }

        String type = String.valueOf(in.getOrDefault("type", inferType(in)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", normalizeType(type));
        copy(in, out, "description");
        copy(in, out, "enum");

        if ("object".equals(out.get("type"))) {
            Map<String, Object> props = properties(in.get("properties"), root, depth);
            out.put("properties", props);
            out.put("required", required(in.get("required"), props));
            out.put("additionalProperties", booleanOr(in.get("additionalProperties"), props.isEmpty()));
        } else if ("array".equals(out.get("type"))) {
            out.put("items", asObject(node(in.getOrDefault("items", Map.of("type", "string")), root, depth + 1)));
        }
        return out;
    }

    private static Map<String, Object> properties(Object raw, Map<String, Object> root, int depth) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), asObject(node(v, root, depth + 1))));
        return out;
    }

    private static List<String> required(Object raw, Map<String, Object> props) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String key = String.valueOf(item);
            if (props.containsKey(key)) out.add(key);
        }
        return out;
    }

    private static Object ref(String ref, Map<String, Object> root) {
        if (!ref.startsWith("#/")) return null;
        Object cur = root;
        for (String part : ref.substring(2).split("/")) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = map.get(part.replace("~1", "/").replace("~0", "~"));
        }
        return cur;
    }

    private static Object pick(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && "null".equals(String.valueOf(map.get("type")))) continue;
            return item;
        }
        return null;
    }

    private static Object mergeDescription(Map<String, Object> in, Object resolved) {
        if (!(resolved instanceof Map<?, ?> map)) return resolved;
        Map<String, Object> out = new LinkedHashMap<>(toStringMap(map));
        if (in.containsKey("description")) out.put("description", in.get("description"));
        return out;
    }

    private static Map<String, Object> fallback(Map<String, Object> in, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        copy(in, out, "description");
        return out;
    }

    private static String inferType(Map<String, Object> in) {
        if (in.containsKey("properties")) return "object";
        if (in.containsKey("items")) return "array";
        return "string";
    }

    private static String normalizeType(String type) {
        return switch (type) {
            case "object", "array", "string", "integer", "number", "boolean" -> type;
            default -> "string";
        };
    }

    private static boolean booleanOr(Object value, boolean def) {
        return value instanceof Boolean b ? b : def;
    }

    private static void copy(Map<String, Object> in, Map<String, Object> out, String key) {
        Object value = in.get(key);
        if (value != null) out.put(key, value);
    }

    private static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> map ? toStringMap(map) : Map.of("type", "string");
    }

    private static Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
