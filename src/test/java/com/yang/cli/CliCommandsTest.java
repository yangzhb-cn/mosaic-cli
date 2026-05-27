package com.yang.cli;

import com.yang.agent.Agent;
import com.yang.llm.LlmClient;
import com.yang.session.SessionManager;
import com.yang.audit.ToolAudit;
import com.yang.memory.MemoryManager;
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
            assertTrue(CliRouter.handle("/mcp", null, null, null, McpManager.empty()));
        } finally {
            System.setOut(original);
        }

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("MCP: 未配置"));
    }

    @Test
    void planCommandWaitsForTaskAndActWithoutPlanReportsEmpty() throws Exception {
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp));

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/plan", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
            assertTrue(agent.isPlanMode());
            assertTrue(CliRouter.handle("/act", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
        } finally {
            System.setOut(original);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("请输入要规划的任务"));
        assertTrue(text.contains("当前没有可执行计划"));
        assertTrue(agent.isPlanMode());
    }

    @Test
    void planWithInlineTaskIsNotSupportedAndCancelKeepsMessagesAndAudit() throws Exception {
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp));
        agent.messages.add(Map.of("role", "user", "content", "hello"));
        agent.audit().record("Read", true, 1_000_000);

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/plan do it", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
            assertTrue(CliRouter.handle("/plan", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
            assertTrue(CliRouter.handle("/cancel", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
        } finally {
            System.setOut(original);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("暂不支持 /plan <task>"));
        assertTrue(text.contains("已取消当前计划"));
        assertEquals(1, agent.messages.size());
        assertFalse(agent.audit().isEmpty());
        assertFalse(agent.isPlanMode());
    }

    @Test
    void resetCommandClearsMessagesAndPlanMode() throws Exception {
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp));
        agent.messages.add(Map.of("role", "user", "content", "hello"));
        agent.enterPlanMode();

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/reset", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
        } finally {
            System.setOut(original);
        }

        assertTrue(agent.messages.isEmpty());
        assertFalse(agent.isPlanMode());
        assertTrue(agent.audit().isEmpty());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("对话已清空"));
    }

    @Test
    void auditCommandPrintsTable() throws Exception {
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp));
        agent.audit().record("mcp_filesystem_directory_tree", true, 32_800_000);
        agent.audit().record("Read", true, 1_000_000);
        agent.audit().record("Read", false, 3_000_000);

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/audit", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
        } finally {
            System.setOut(original);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Tool"));
        assertTrue(text.contains("Calls"));
        assertTrue(text.contains("Success_Rate"));
        assertTrue(text.contains("Read"));
        assertTrue(text.contains("50.00%"));
        List<String> lines = text.strip().lines().toList();
        assertEquals(lines.get(1).indexOf("1"), lines.get(2).indexOf("2"));
    }

    @Test
    void auditSaveAppendsJsonlForSameConversation() throws Exception {
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp.resolve("audits"), "conversation_test"));
        agent.audit().record("Read", true, 1_000_000);

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/audit save", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
            assertTrue(CliRouter.handle("/audit save", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
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
    void sessionSwitchRestoresMessagesAndConversationId() throws Exception {
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        sessions.create("session-one", "test-model");
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "hello"));
        sessions.saveActive(messages, "test-model", "conversation_keep");
        sessions.create("session-two", "test-model");
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp.resolve("audits")));
        agent.audit().record("Read", true, 1_000_000);

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/session switch session-one", agent, new FakeLlm(""), sessions));
        } finally {
            System.setOut(original);
        }

        assertEquals(messages, agent.messages);
        assertEquals("conversation_keep", agent.conversationId());
        assertFalse(agent.isPlanMode());
        assertTrue(agent.audit().isEmpty());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("已切换到会话"));
    }

    @Test
    void sessionListPrintsSessionsAndActiveMarker() throws Exception {
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        sessions.create("session-one", "test-model");
        sessions.saveActive(List.of(Map.of("role", "user", "content", "hello session")), "test-model", "conversation_keep");

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/session list", null, new FakeLlm(""), sessions));
        } finally {
            System.setOut(original);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("session-one"));
        assertTrue(text.contains("test-model"));
        assertTrue(text.contains("hello session"));
        assertTrue(text.contains("*"));
    }

    @Test
    void sessionCommandPrintsCurrentUserMessagesWithoutSystemReminder() throws Exception {
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp));
        agent.messages.add(Map.of("role", "user", "content", "<system-reminder>\nsecret\n</system-reminder>\n\nhello"));
        agent.messages.add(Map.of("role", "assistant", "content", "hidden"));
        agent.messages.add(Map.of("role", "user", "content", "second"));

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/session", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
        } finally {
            System.setOut(original);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("1. hello"));
        assertTrue(text.contains("2. second"));
        assertFalse(text.contains("system-reminder"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("hidden"));
    }

    @Test
    void sessionNewCreatesAndSwitchesWithoutDeletingOldSession() throws Exception {
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        sessions.create("old", "test-model");
        sessions.saveActive(List.of(Map.of("role", "user", "content", "saved user")), "test-model", "conversation_saved");
        Agent agent = new Agent(new FakeLlm(""), 1000, null, List.of(), List.of(), new ToolAudit(temp));
        agent.messages.add(Map.of("role", "user", "content", "current user"));

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/session new fresh", agent, new FakeLlm(""), sessions));
        } finally {
            System.setOut(original);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("fresh"));
        assertTrue(agent.messages.isEmpty());
        assertEquals("fresh", sessions.activeId());
        assertNotNull(sessions.load("old"));
    }

    @Test
    void memoryUpdateCommandUpdatesWorkspaceMemory() throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));
        memory.ensureWorkspace();
        Agent agent = new Agent(new ChatFakeLlm("# Updated Memory"), 1000, null, List.of(), List.of(), new ToolAudit(temp), memory);
        agent.messages.add(Map.of("role", "user", "content", "remember concise"));

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertTrue(CliRouter.handle("/memory update", agent, new FakeLlm(""), new SessionManager(temp.resolve("data"))));
        } finally {
            System.setOut(original);
        }

        assertEquals("# Updated Memory", memory.readMemory());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("长期记忆已更新"));
    }

    @Test
    void removedSessionCommandsAreNoLongerHandled() throws Exception {
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        assertFalse(CliRouter.handle("/sessions", null, new FakeLlm(""), sessions));
        assertFalse(CliRouter.handle("/save", null, new FakeLlm(""), sessions));
        assertFalse(CliRouter.handle("/load session-one", null, new FakeLlm(""), sessions));
    }

    private static final class FakeLlm extends LlmClient {
        private FakeLlm(String ignored) {
            super("test-model", "key", "http://localhost", 0);
        }
    }

    private static final class ChatFakeLlm extends LlmClient {
        private final String content;

        private ChatFakeLlm(String content) {
            super("test-model", "key", "http://localhost", 0);
            this.content = content;
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, java.util.function.Consumer<String> onToken, ToolReady onToolReady) {
            return new Response(content, "", List.of(), 0, 0, 0);
        }
    }
}
