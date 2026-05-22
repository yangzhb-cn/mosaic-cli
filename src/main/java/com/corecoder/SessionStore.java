package com.corecoder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class SessionStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private final Path dir;

    public record Session(List<Map<String, Object>> messages, String model) {}
    public record SessionInfo(String id, String model, String savedAt, String preview) {}

    public SessionStore() {
        this(Path.of(System.getProperty("user.home"), ".corecoder", "sessions"));
    }

    public SessionStore(Path dir) {
        this.dir = dir;
    }

    public String save(List<Map<String, Object>> messages, String model, String id) throws IOException {
        Files.createDirectories(dir);
        String sid = normalize(id);
        Map<String, Object> data = Map.of(
                "id", sid,
                "model", model,
                "saved_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                "messages", messages
        );
        JSON.writerWithDefaultPrettyPrinter().writeValue(path(sid).toFile(), data);
        return sid;
    }

    public Session load(String id) throws IOException {
        Path p = path(id);
        if (!Files.exists(p)) return null;
        Map<String, Object> data = JSON.readValue(p.toFile(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) data.get("messages");
        return new Session(messages, String.valueOf(data.get("model")));
    }

    public List<SessionInfo> list() throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .limit(20)
                    .map(this::info)
                    .filter(i -> i != null)
                    .toList();
        }
    }

    private SessionInfo info(Path p) {
        try {
            Map<String, Object> data = JSON.readValue(p.toFile(), new TypeReference<>() {});
            String preview = "";
            Object raw = data.get("messages");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m && "user".equals(m.get("role")) && m.get("content") != null) {
                        preview = String.valueOf(m.get("content"));
                        if (preview.length() > 80) preview = preview.substring(0, 80);
                        break;
                    }
                }
            }
            return new SessionInfo(
                    String.valueOf(data.getOrDefault("id", p.getFileName().toString().replaceFirst("\\.json$", ""))),
                    String.valueOf(data.getOrDefault("model", "?")),
                    String.valueOf(data.getOrDefault("saved_at", "?")),
                    preview
            );
        } catch (IOException e) {
            return null;
        }
    }

    private Path path(String id) {
        Path p = dir.resolve(normalize(id) + ".json").toAbsolutePath().normalize();
        Path root = dir.toAbsolutePath().normalize();
        if (!p.getParent().equals(root)) throw new IllegalArgumentException("会话 ID 无效");
        return p;
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) return "session_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String s = id.trim().replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        s = UNSAFE.matcher(s).replaceAll("-").replaceAll("^[._-]+|[._-]+$", "");
        return s.isBlank() ? normalize(null) : s;
    }
}
