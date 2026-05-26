package com.yang.tool;

import com.yang.llm.LlmClient;
import com.yang.audit.ToolAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {
    @TempDir
    Path temp;

    @Test
    void rejectsUnknownArgumentsBeforeExecute() {
        StrictTool tool = new StrictTool();
        ToolExecutor executor = new ToolExecutor(List.of(tool));

        String result = executor.execute(new LlmClient.ToolCall("call_1", "Strict", Map.of(
                "input", "ok",
                "extra", "ignored"
        )), null);

        assertEquals(0, tool.executions);
        assertTrue(result.contains("未知参数 Strict.extra"));
    }

    @Test
    void rejectsMissingRequiredArgumentsBeforeExecute() {
        StrictTool tool = new StrictTool();
        ToolExecutor executor = new ToolExecutor(List.of(tool));

        String result = executor.execute(new LlmClient.ToolCall("call_1", "Strict", Map.of()), null);

        assertEquals(0, tool.executions);
        assertTrue(result.contains("缺少必填参数 Strict.input"));
    }

    @Test
    void rejectsWrongArgumentTypesBeforeExecute() {
        StrictTool tool = new StrictTool();
        ToolExecutor executor = new ToolExecutor(List.of(tool));

        String result = executor.execute(new LlmClient.ToolCall("call_1", "Strict", Map.of(
                "input", 123
        )), null);

        assertEquals(0, tool.executions);
        assertTrue(result.contains("Strict.input 必须是 string"));
    }

    @Test
    void recordsAuditStatsForToolOutcomes() {
        ToolAudit audit = new ToolAudit(temp);
        ToolExecutor executor = new ToolExecutor(List.of(new StrictTool(), new ThrowingTool(), new ErrorTool()), audit);

        executor.execute(new LlmClient.ToolCall("call_1", "Strict", Map.of("input", "ok")), null);
        executor.execute(new LlmClient.ToolCall("call_2", "Strict", Map.of("extra", "bad")), null);
        executor.execute(new LlmClient.ToolCall("call_3", "Missing", Map.of()), null);
        executor.execute(new LlmClient.ToolCall("call_4", "Throwing", Map.of()), null);
        executor.execute(new LlmClient.ToolCall("call_5", "Error", Map.of()), null);

        Map<String, Map<String, Object>> rows = audit.records().stream()
                .collect(Collectors.toMap(r -> String.valueOf(r.get("Tool")), r -> r));
        assertEquals(2, rows.get("Strict").get("Calls"));
        assertEquals(1, rows.get("Strict").get("Success"));
        assertEquals("50.00%", rows.get("Strict").get("Success_Rate"));
        assertEquals(1, rows.get("Missing").get("Calls"));
        assertEquals(0, rows.get("Missing").get("Success"));
        assertEquals(0, rows.get("Throwing").get("Success"));
        assertEquals(0, rows.get("Error").get("Success"));
    }

    private static final class StrictTool implements Tools.Tool {
        private int executions;

        @Override
        public String name() {
            return "Strict";
        }

        @Override
        public String description() {
            return "Strict test tool";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "properties", Map.of("input", Map.of("type", "string")),
                    "required", List.of("input")
            );
        }

        @Override
        public String execute(Map<String, Object> args) {
            executions++;
            return "executed";
        }
    }

    private static final class ThrowingTool implements Tools.Tool {
        @Override
        public String name() {
            return "Throwing";
        }

        @Override
        public String description() {
            return "Throwing test tool";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        @Override
        public String execute(Map<String, Object> args) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class ErrorTool implements Tools.Tool {
        @Override
        public String name() {
            return "Error";
        }

        @Override
        public String description() {
            return "Error test tool";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "错误: failed";
        }
    }

}
