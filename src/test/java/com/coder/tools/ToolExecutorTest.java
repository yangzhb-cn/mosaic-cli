package com.coder.tools;

import com.coder.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {
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
}
