package com.coder.cli;

import com.coder.LlmClient;
import com.coder.mcp.McpManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CliCommandsTest {
    @Test
    void mcpCommandPrintsStatus() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliCommands.handle("/mcp", null, null, null, McpManager.empty()));
        } finally {
            System.setOut(original);
        }

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("MCP: 未配置"));
    }

    @Test
    void lastRequestCommandPrintsJson() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliCommands.handle("/last-request", null, new FakeLlm("{\"messages\":[{\"role\":\"system\"}]}"), null));
        } finally {
            System.setOut(original);
        }

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"role\":\"system\""));
    }

    @Test
    void lastRequestCommandHandlesEmptyRequest() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliCommands.handle("/last-request", null, new FakeLlm(""), null));
        } finally {
            System.setOut(original);
        }

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("暂无 LLM 请求"));
    }

    private static final class FakeLlm extends LlmClient {
        private final String lastRequestJson;

        private FakeLlm(String lastRequestJson) {
            super("test-model", "key", "http://localhost", 0);
            this.lastRequestJson = lastRequestJson;
        }

        @Override
        public String lastRequestJson() {
            return lastRequestJson;
        }
    }
}
