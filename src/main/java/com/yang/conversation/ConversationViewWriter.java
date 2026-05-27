package com.yang.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 从 session JSONL 派生可读 Markdown 视图，不参与 session 恢复。 */
public final class ConversationViewWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path conversationsDir;

    public ConversationViewWriter(Path conversationsDir) {
        this.conversationsDir = conversationsDir == null ? null : conversationsDir.toAbsolutePath().normalize();
    }

    public void write(Path sessionJsonl, String sessionId) throws Exception {
        if (conversationsDir == null || sessionJsonl == null || sessionId == null || sessionId.isBlank()) return;
        Path out = conversationsDir.resolve(sessionId).resolve("conversation.md").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, render(sessionJsonl, sessionId), StandardCharsets.UTF_8);
    }

    public Path viewPath(String sessionId) {
        return conversationsDir.resolve(sessionId).resolve("conversation.md").toAbsolutePath().normalize();
    }

    private static String render(Path sessionJsonl, String sessionId) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("# Conversation: ").append(sessionId).append("\n\n");
        out.append("- Source: `").append(sessionJsonl.toAbsolutePath().normalize()).append("`\n\n");
        out.append("---\n");
        for (String line : Files.readAllLines(sessionJsonl, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Map<String, Object> event = JSON.readValue(line, new TypeReference<>() {});
            appendEvent(out, event);
        }
        return out.toString().stripTrailing() + "\n";
    }

    private static void appendEvent(StringBuilder out, Map<String, Object> event) {
        String timestamp = String.valueOf(event.getOrDefault("timestamp", ""));
        String type = String.valueOf(event.getOrDefault("type", ""));
        Map<String, Object> payload = asMap(event.get("payload"));
        switch (type) {
            case "session_meta" -> appendMeta(out, timestamp, payload);
            case "response_item" -> appendMessage(out, timestamp, asMap(payload.get("message")));
            default -> {
            }
        }
    }

    private static void appendMeta(StringBuilder out, String timestamp, Map<String, Object> payload) {
        out.append("\n## Session Meta\n\n");
        out.append("- Time: ").append(value(timestamp)).append('\n');
        out.append("- Model: ").append(value(payload.get("model"))).append('\n');
        out.append("- Conversation: ").append(value(payload.get("conversation_id"))).append('\n');
        out.append("- CWD: `").append(value(payload.get("cwd"))).append("`\n");
    }

    private static void appendMessage(StringBuilder out, String timestamp, Map<String, Object> message) {
        String role = String.valueOf(message.getOrDefault("role", ""));
        if (!"user".equals(role) && !"assistant".equals(role)) return;
        String title = "user".equals(role) ? "User" : "Assistant";
        out.append("\n## ").append(value(timestamp)).append("\n\n");
        out.append("### ").append(title).append("\n\n");
        appendText(out, message.get("content"));
    }

    private static void appendText(StringBuilder out, Object text) {
        String content = value(text);
        if (content.isBlank()) {
            out.append("_No content._\n");
        } else {
            out.append(content).append('\n');
        }
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
