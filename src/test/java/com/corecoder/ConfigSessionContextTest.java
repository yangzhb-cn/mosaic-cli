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
        assertEquals("deepseek-v4-flash", c.model);
        assertEquals("", c.apiKey);
        assertEquals("https://api.deepseek.com/v1", c.baseUrl);
        assertEquals(0.0, c.temperature);
        assertEquals(128000, c.maxContextTokens);
    }

    @Test
    void configLoadsDotenvAndEnvWins() throws Exception {
        Files.writeString(temp.resolve(".env"), """
                DEEPSEEK_API_KEY=file-key
                DEEPSEEK_BASE_URL=https://file.example/v1
                TELEGRAM_BOT_TOKEN=file-token
                OWNER_ID=file-owner
                """);

        Config c = Config.from(Map.of(
                "DEEPSEEK_API_KEY", "env-key",
                "OWNER_ID", "env-owner"
        ), temp);
        assertEquals("deepseek-v4-flash", c.model);
        assertEquals("env-key", c.apiKey);
        assertEquals("https://file.example/v1", c.baseUrl);
        assertEquals("telegram", c.im);
        assertEquals("file-token", c.telegramBotToken);
        assertEquals("env-owner", c.telegramOwnerId);
        assertTrue(c.telegramEnabled());
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
