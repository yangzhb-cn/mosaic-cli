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
        assertTrue(Files.isRegularFile(temp.resolve("data/sessions").resolve(session.id() + ".json")));
        assertEquals(session.id(), manager.activeId());
        assertTrue(session.messages().isEmpty());
    }

    @Test
    void saveActiveAndRestartRestoreMessagesAndConversationId() throws Exception {
        Path data = temp.resolve("data");
        SessionManager manager = new SessionManager(data);
        SessionManager.Session created = manager.loadActiveOrCreate("test-model");
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "hello"));

        manager.saveActive(messages, "test-model", "conversation_keep");

        SessionManager restarted = new SessionManager(data);
        SessionManager.Session restored = restarted.loadActiveOrCreate("other-model");
        assertEquals(created.id(), restored.id());
        assertEquals(messages, restored.messages());
        assertEquals("conversation_keep", restored.conversationId());
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
}
