package com.yang.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    @TempDir
    Path temp;

    @Test
    void loadActiveOrCreateCreatesStateAndSessionWhenMissing() throws Exception {
        SessionManager manager = new SessionManager(temp.resolve("data"));

        SessionManager.Session session = manager.loadActiveOrCreate("test-model");

        assertFalse(session.id().isBlank());
        assertTrue(Files.isRegularFile(temp.resolve("data/state.json")));
        assertEquals(1, jsonlFiles(temp.resolve("data")).size());
        assertEquals(session.id(), manager.activeId());
        assertTrue(session.messages().isEmpty());
        assertTrue(Files.readString(temp.resolve("data/state.json")).contains("active_session_path"));
    }

    @Test
    void saveActiveAndRestartRestoreMessagesAndConversationId() throws Exception {
        Path data = temp.resolve("data");
        SessionManager manager = new SessionManager(data);
        SessionManager.Session created = manager.loadActiveOrCreate("test-model");
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "tool", "tool_call_id", "call_1", "content", "tool result")
        );
        List<Map<String, Object>> audit = List.of(Map.of(
                "Tool", "Read",
                "Calls", 2,
                "Success", 1,
                "Success_Rate", "50.00%",
                "Avg_ms", 3.5
        ));

        manager.saveActive(messages, "test-model", "conversation_keep", audit);

        SessionManager restarted = new SessionManager(data);
        SessionManager.Session restored = restarted.loadActiveOrCreate("other-model");
        assertEquals(created.id(), restored.id());
        assertEquals(messages, restored.messages());
        assertEquals("conversation_keep", restored.conversationId());
        assertEquals(audit, restored.auditRecords());
        String jsonl = Files.readString(jsonlFiles(data).getFirst());
        assertTrue(jsonl.contains("\"type\":\"response_item\""));
        assertTrue(jsonl.contains("\"type\":\"audit_snapshot\""));
        assertTrue(jsonl.contains("tool result"));
        String view = Files.readString(conversationView(data, created.id()));
        assertTrue(view.contains("# Conversation: " + created.id()));
        assertTrue(view.contains("### User"));
        assertTrue(view.contains("hello"));
        assertFalse(view.contains("### Tool Result"));
        assertFalse(view.contains("tool result"));
    }

    @Test
    void createNewSessionDoesNotDeleteOldAndRejectsDuplicateIds() throws Exception {
        SessionManager manager = new SessionManager(temp.resolve("data"));
        SessionManager.Session first = manager.create("first", "m");
        SessionManager.Session second = manager.create("second", "m");

        assertNotEquals(first.id(), second.id());
        assertEquals("second", manager.activeId());
        assertEquals(2, manager.list().size());
        assertThrows(IllegalArgumentException.class, () -> manager.create("first", "m"));
    }

    @Test
    void switchToRestoresExistingSessionAndMarksActive() throws Exception {
        SessionManager manager = new SessionManager(temp.resolve("data"));
        manager.create("a", "m");
        manager.saveActive(List.of(Map.of("role", "user", "content", "a msg")), "m", "conversation_a");
        manager.create("b", "m");

        SessionManager.Session switched = manager.switchTo("a");

        assertNotNull(switched);
        assertEquals("a", manager.activeId());
        assertEquals("conversation_a", switched.conversationId());
        assertEquals("a msg", switched.messages().getFirst().get("content"));
        assertTrue(manager.list().stream().anyMatch(s -> s.id().equals("a") && s.active()));
    }

    @Test
    void resetActiveKeepsOldHistoryButReplayStartsAfterReset() throws Exception {
        Path data = temp.resolve("data");
        SessionManager manager = new SessionManager(data);
        SessionManager.Session session = manager.create("reset-me", "m");
        manager.saveActive(List.of(Map.of("role", "user", "content", "before")), "m", "conversation_before");

        manager.resetActive("conversation_after", List.of());

        SessionManager.Session restored = manager.load(session.id());
        assertTrue(restored.messages().isEmpty());
        assertEquals("conversation_after", restored.conversationId());
        String jsonl = Files.readString(jsonlFiles(data).getFirst());
        assertTrue(jsonl.contains("before"));
        assertTrue(jsonl.contains("\"type\":\"session_reset\""));
        String view = Files.readString(conversationView(data, session.id()));
        assertFalse(view.contains("## Session Reset"));
        assertTrue(view.contains("before"));
    }

    @Test
    void saveActiveWritesContextResetWhenMessagesWereCompressed() throws Exception {
        Path data = temp.resolve("data");
        SessionManager manager = new SessionManager(data);
        SessionManager.Session session = manager.create("compact", "m");
        manager.saveActive(List.of(Map.of("role", "user", "content", "original")), "m", "conversation_a");

        List<Map<String, Object>> compressed = List.of(Map.of("role", "user", "content", "[上下文已压缩]"));
        manager.saveActive(compressed, "m", "conversation_a");

        SessionManager.Session restored = manager.load(session.id());
        assertEquals(compressed, restored.messages());
        String jsonl = Files.readString(jsonlFiles(data).getFirst());
        assertTrue(jsonl.contains("\"type\":\"context_reset\""));
        String view = Files.readString(conversationView(data, session.id()));
        assertFalse(view.contains("## Context Reset"));
        assertTrue(view.contains("[上下文已压缩]"));
    }

    @Test
    void recordEventRefreshesConversationViewWithoutAffectingReplay() throws Exception {
        Path data = temp.resolve("data");
        SessionManager manager = new SessionManager(data);
        SessionManager.Session session = manager.create("events", "m");
        manager.saveActive(List.of(Map.of("role", "user", "content", "hello")), "m", "conversation_events");

        manager.recordEvent("tool_call", Map.of("name", "Read", "arguments", Map.of("file_path", "README.md")));

        SessionManager.Session restored = manager.load(session.id());
        assertEquals(1, restored.messages().size());
        assertEquals("hello", restored.messages().getFirst().get("content"));
        String view = Files.readString(conversationView(data, session.id()));
        assertFalse(view.contains("### Tool Call"));
        assertFalse(view.contains("Read"));
        assertFalse(view.contains("README.md"));
    }

    @Test
    void migratesLegacyJsonSessionToJsonl() throws Exception {
        Path data = temp.resolve("data");
        Path sessions = data.resolve("sessions");
        Files.createDirectories(sessions);
        Files.writeString(sessions.resolve("legacy.json"), """
                {
                  "id": "legacy",
                  "model": "m",
                  "conversation_id": "conversation_legacy",
                  "created_at": "2026-05-27 10:00:00",
                  "updated_at": "2026-05-27 10:00:00",
                  "messages": [{"role":"user","content":"old message"}],
                  "audit_records": [{"Tool":"Read","Calls":1,"Success":1,"Success_Rate":"100.00%","Avg_ms":1.0}]
                }
                """);
        Files.writeString(data.resolve("state.json"), "{\"active_session_id\":\"legacy\"}");

        SessionManager manager = new SessionManager(data);
        SessionManager.Session session = manager.loadActiveOrCreate("new-model");

        assertEquals("legacy", session.id());
        assertEquals("conversation_legacy", session.conversationId());
        assertEquals("old message", session.messages().getFirst().get("content"));
        assertEquals(1, jsonlFiles(data).size());
        assertTrue(Files.exists(conversationView(data, "legacy")));
    }

    private static List<Path> jsonlFiles(Path data) throws Exception {
        try (var stream = Files.walk(data.resolve("sessions"))) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
        }
    }

    private static Path conversationView(Path data, String sessionId) {
        return data.getParent().resolve("workspace/conversations").resolve(sessionId).resolve("conversation.md");
    }
}
