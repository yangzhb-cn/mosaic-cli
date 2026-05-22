package com.corecoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSessionContextTest {
    @TempDir
    Path temp;

    @Test
    void configUsesDefaultsWithoutEnvOrDotenv() {
        Config c = Config.from(Map.of(), temp);
        assertEquals("gpt-4o", c.model);
        assertEquals("", c.apiKey);
        assertNull(c.baseUrl);
        assertEquals(4096, c.maxTokens);
        assertEquals(0.0, c.temperature);
        assertEquals(128000, c.maxContextTokens);
    }

    @Test
    void configLoadsDotenvAndEnvWins() throws Exception {
        Files.writeString(temp.resolve(".env"), """
                CORECODER_MODEL=file-model
                OPENAI_API_KEY=file-key
                CORECODER_MAX_TOKENS=1000
                """);

        Config c = Config.from(Map.of("CORECODER_MODEL", "env-model"), temp);
        assertEquals("env-model", c.model);
        assertEquals("file-key", c.apiKey);
        assertEquals(1000, c.maxTokens);
    }

    @Test
    void sessionsSaveLoadListAndSanitizeIds() throws Exception {
        SessionStore store = new SessionStore(temp);
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "hello"));

        String id = store.save(messages, "test-model", "../Research Notes!");
        assertEquals("Research-Notes", id);

        SessionStore.Session loaded = store.load("../Research Notes!");
        assertNotNull(loaded);
        assertEquals("test-model", loaded.model());
        assertEquals(messages, loaded.messages());
        assertEquals(1, store.list().size());
    }

    @Test
    void defaultSessionIdsDoNotCollide() throws Exception {
        SessionStore store = new SessionStore(temp);
        String a = store.save(List.of(Map.of("role", "user", "content", "a")), "m", null);
        String b = store.save(List.of(Map.of("role", "user", "content", "b")), "m", null);
        assertNotEquals(a, b);
    }

    @Test
    void contextSnipsAndCompressesLargeToolOutput() {
        ContextManager ctx = new ContextManager(2000);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(msg("user", "msg " + i + " " + "a".repeat(200)));
            Map<String, Object> tool = msg("tool", "b".repeat(2000));
            tool.put("tool_call_id", "t" + i);
            messages.add(tool);
        }

        int before = ContextManager.estimateTokens(messages);
        assertTrue(ctx.maybeCompress(messages, null));
        assertTrue(ContextManager.estimateTokens(messages) < before);
        assertTrue(messages.size() < 24);
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
