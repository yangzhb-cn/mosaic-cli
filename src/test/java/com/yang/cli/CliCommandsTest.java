package com.yang.cli;

import com.yang.Agent;
import com.yang.LlmClient;
import com.yang.SessionStore;
import com.yang.audit.ToolAudit;
import com.yang.mcp.McpManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliCommandsTest {
    @TempDir
    Path temp;

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

    @Test
    void auditCommandPrintsTable() throws Exception {
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp));
        agent.audit().record("Read", true, 1_000_000);
        agent.audit().record("Read", false, 3_000_000);

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliCommands.handle("/audit", agent, new FakeLlm(""), new SessionStore(temp.resolve("sessions"))));
        } finally {
            System.setOut(original);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Tool"));
        assertTrue(text.contains("Calls"));
        assertTrue(text.contains("Success_Rate"));
        assertTrue(text.contains("Read"));
        assertTrue(text.contains("50.00%"));
    }

    @Test
    void auditSaveAppendsJsonlForSameConversation() throws Exception {
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp.resolve("audits"), "conversation_test"));
        agent.audit().record("Read", true, 1_000_000);

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliCommands.handle("/audit save", agent, new FakeLlm(""), new SessionStore(temp.resolve("sessions"))));
            assertTrue(CliCommands.handle("/audit save", agent, new FakeLlm(""), new SessionStore(temp.resolve("sessions"))));
        } finally {
            System.setOut(original);
        }

        Path auditFile = temp.resolve("audits").resolve("audit_conversation_test.jsonl");
        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().contains("\"time\""));
        assertTrue(lines.getFirst().contains("\"Tool\":\"Read\""));
        assertTrue(lines.getFirst().contains("\"Success_Rate\":\"100.00%\""));
    }

    @Test
    void loadCommandRestoresMessagesAndConversationId() throws Exception {
        SessionStore sessions = new SessionStore(temp.resolve("sessions"));
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "hello"));
        sessions.save(messages, "test-model", "session-one", "conversation_keep");
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp.resolve("audits")));
        agent.audit().record("Read", true, 1_000_000);

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliCommands.handle("/load session-one", agent, new FakeLlm(""), sessions));
        } finally {
            System.setOut(original);
        }

        assertEquals(messages, agent.messages);
        assertEquals("conversation_keep", agent.conversationId());
        assertTrue(agent.audit().isEmpty());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("会话已加载"));
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
