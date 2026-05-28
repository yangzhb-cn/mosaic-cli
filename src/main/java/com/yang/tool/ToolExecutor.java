package com.yang.tool;

import com.yang.llm.LlmClient;
import com.yang.audit.ToolAudit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

/** 负责工具参数校验、并发执行、结果收集和审计记录。 */
public final class ToolExecutor {
    private final List<Tools.Tool> tools;
    private final ToolAudit audit;

    /** 工具执行事件：accept 表示开始，finished 表示结束。 */
    public interface ToolObserver extends BiConsumer<String, Map<String, Object>> {
        default void finished(String name, Map<String, Object> args, boolean success, long elapsedNanos, String result) {
        }
    }

    public ToolExecutor(List<Tools.Tool> tools) {
        this(tools, null);
    }

    public ToolExecutor(List<Tools.Tool> tools, ToolAudit audit) {
        this.tools = tools;
        this.audit = audit;
    }

    public List<Map<String, Object>> schemas() {
        return tools.stream().map(Tools.Tool::schema).toList();
    }

    // 把一个工具调用按 index 提交到线程池；已提交过则直接复用原 Future。
    public void submit(Map<Integer, Future<String>> futures, ExecutorService pool, int idx, LlmClient.ToolCall tc, ToolObserver onTool) {
        // computeIfAbsent 保证同一个 index 不会因为流式回调和兜底逻辑(未流式调用)被执行两次。
        futures.computeIfAbsent(idx, ignored -> pool.submit(() -> execute(tc, onTool)));
    }

    public void submit(Map<Integer, Future<String>> futures, ExecutorService pool, int idx, LlmClient.ToolCall tc, BiConsumer<String, Map<String, Object>> onTool) {
        submit(futures, pool, idx, tc, observer(onTool));
    }

    // 按模型返回的工具顺序收集结果，保证写回 messages 的 tool_result 顺序稳定。
    public List<String> collectResults(List<LlmClient.ToolCall> calls, Map<Integer, Future<String>> futures, ExecutorService pool, ToolObserver onTool) throws Exception {
        // 结果列表顺序必须和 tool_calls 顺序一致。
        List<String> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            // 如果该工具没有在 SSE 读取阶段提前启动，这里按旧逻辑启动。
            submit(futures, pool, i, calls.get(i), onTool);
            // 即使后面的工具先完成，也按 index 顺序等待和写回。
            results.add(futures.get(i).get());
        }
        return results;
    }

    public List<String> collectResults(List<LlmClient.ToolCall> calls, Map<Integer, Future<String>> futures, ExecutorService pool, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        return collectResults(calls, futures, pool, observer(onTool));
    }

    String execute(LlmClient.ToolCall tc, ToolObserver onTool) {
        long started = System.nanoTime();
        boolean success = false;
        String result = "";
        Map<String, Object> args = tc.arguments() == null ? Map.of() : tc.arguments();
        try {
            if (onTool != null) onTool.accept(tc.name(), args);
            // 根据工具名从工具列表里查找实际工具对象
            Tools.Tool tool = Tools.get(tools, tc.name());
            if (tool == null) {
                result = "错误: 未知工具 '" + tc.name() + "'";
                return result;
            }
            String error = validateArgs(tool, args);
            if (error != null) {
                result = "错误: " + error;
                return result;
            }
            try {
                // 执行工具，如果执行成功，返回工具结果字符串
                result = tool.execute(args);
                success = result == null || !result.startsWith("错误:");
                return result == null ? "" : result;
            } catch (Exception e) {
                // 如果异常，返回格式化错误信息，而不是直接抛出异常
                // 这样可以让模型看到工具失败原因，并尝试修正
                result = "错误: 执行工具 " + tc.name() + " 失败: " + e.getMessage();
                return result;
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            if (audit != null) audit.record(tc.name(), success, elapsed);
            if (onTool != null) onTool.finished(tc.name(), args, success, elapsed, result);
        }
    }

    private static ToolObserver observer(BiConsumer<String, Map<String, Object>> onTool) {
        if (onTool == null) return null;
        if (onTool instanceof ToolObserver observer) return observer;
        return onTool::accept;
    }

    private String validateArgs(Tools.Tool tool, Map<String, Object> args) {
        String error = validateObject(tool.name(), args == null ? Map.of() : args, asMap(tool.parameters()));
        return error == null ? null : tool.name() + " 参数不合法: " + error;
    }

    private String validateObject(String path, Map<String, Object> value, Map<String, Object> schema) {
        Map<String, Object> props = asMap(schema.get("properties"));
        for (String key : asStringList(schema.get("required"))) {
            if (!value.containsKey(key) || value.get(key) == null) return "缺少必填参数 " + path + "." + key;
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            for (String key : value.keySet()) {
                if (!props.containsKey(key)) return "未知参数 " + path + "." + key + "，允许参数: " + String.join(", ", props.keySet());
            }
        }
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Map<String, Object> prop = asMap(props.get(entry.getKey()));
            if (!prop.isEmpty()) {
                String error = validateValue(path + "." + entry.getKey(), entry.getValue(), prop);
                if (error != null) return error;
            }
        }
        return null;
    }

    private String validateValue(String path, Object value, Map<String, Object> schema) {
        if (value == null) return path + " 不能为 null";
        String type = String.valueOf(schema.getOrDefault("type", ""));
        return switch (type) {
            case "string" -> value instanceof String ? null : path + " 必须是 string";
            case "integer" -> isInteger(value) ? null : path + " 必须是 integer";
            case "number" -> value instanceof Number ? null : path + " 必须是 number";
            case "boolean" -> value instanceof Boolean ? null : path + " 必须是 boolean";
            case "array" -> validateArray(path, value, asMap(schema.get("items")));
            case "object" -> value instanceof Map<?, ?> map ? validateObject(path, toStringMap(map), schema) : path + " 必须是 object";
            default -> null;
        };
    }

    private String validateArray(String path, Object value, Map<String, Object> itemSchema) {
        if (!(value instanceof List<?> list)) return path + " 必须是 array";
        if (itemSchema.isEmpty()) return null;
        for (int i = 0; i < list.size(); i++) {
            String error = validateValue(path + "[" + i + "]", list.get(i), itemSchema);
            if (error != null) return error;
        }
        return null;
    }

    private boolean isInteger(Object value) {
        return value instanceof Number n && Math.floor(n.doubleValue()) == n.doubleValue();
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        return toStringMap(map);
    }

    private Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) out.add(String.valueOf(item));
        return out;
    }
}
