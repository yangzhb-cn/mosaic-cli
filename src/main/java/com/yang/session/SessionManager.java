package com.yang.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yang.audit.ToolAudit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 管理项目本地 data/state.json 和 data/sessions 下的自动持久化多会话。 */
public final class SessionManager {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path dataDir;
    private final Path sessionsDir;
    private final Path stateFile;
    private final boolean enabled;
    private String activeId;

    /** 加载出来的完整 session。 */
    public record Session(String id, String model, String conversationId, List<Map<String, Object>> messages,
                          List<Map<String, Object>> auditRecords,
                          String createdAt, String updatedAt) {}

    /** /session list 展示用信息。 */
    public record SessionInfo(String id, String model, String createdAt, String updatedAt, String preview, boolean active) {}

    public SessionManager() {
        this(Path.of("").toAbsolutePath().resolve("data"));
    }

    public SessionManager(Path dataDir) {
        this(dataDir, true);
    }

    private SessionManager(Path dataDir, boolean enabled) {
        this.dataDir = dataDir == null ? null : dataDir.toAbsolutePath().normalize();
        this.sessionsDir = this.dataDir == null ? null : this.dataDir.resolve("sessions");
        this.stateFile = this.dataDir == null ? null : this.dataDir.resolve("state.json");
        this.enabled = enabled;
    }

    public static SessionManager disabled() {
        return new SessionManager(null, false);
    }

    public synchronized Session loadActiveOrCreate(String model) throws IOException {
        if (!enabled) return disabledSession(model);
        Files.createDirectories(sessionsDir);
        activeId = readActiveId();
        Session session = activeId == null ? null : load(activeId);
        if (session != null) return session;
        return create(null, model);
    }

    public synchronized Session create(String id, String model) throws IOException {
        if (!enabled) return disabledSession(model);
        Files.createDirectories(sessionsDir);
        String sid = normalize(id);
        Path file = path(sid);
        if (Files.exists(file)) throw new IllegalArgumentException("会话已存在: " + sid);
        String now = now();
        Session session = new Session(sid, model, ToolAudit.newConversationId(), List.of(), List.of(), now, now);
        write(session);
        writeActiveId(sid);
        activeId = sid;
        return session;
    }

    public synchronized Session switchTo(String id) throws IOException {
        if (!enabled) return null;
        Session session = load(id);
        if (session == null) return null;
        writeActiveId(session.id());
        activeId = session.id();
        return session;
    }

    public synchronized void saveActive(List<Map<String, Object>> messages, String model, String conversationId) throws IOException {
        saveActive(messages, model, conversationId, List.of());
    }

    public synchronized void saveActive(List<Map<String, Object>> messages, String model, String conversationId, List<Map<String, Object>> auditRecords) throws IOException {
        if (!enabled) return;
        Files.createDirectories(sessionsDir);
        if (activeId == null || activeId.isBlank()) {
            activeId = create(null, model).id();
        }
        Session old = load(activeId);
        String createdAt = old == null ? now() : old.createdAt();
        Session session = new Session(activeId, model, conversationId, copyMessages(messages), copyMessages(auditRecords), createdAt, now());
        write(session);
        writeActiveId(activeId);
    }

    public synchronized Session load(String id) throws IOException {
        if (!enabled) return null;
        Path file = path(id);
        if (!Files.exists(file)) return null;
        Map<String, Object> data = JSON.readValue(file.toFile(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) data.getOrDefault("messages", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> auditRecords = (List<Map<String, Object>>) data.getOrDefault("audit_records", List.of());
        return new Session(
                String.valueOf(data.getOrDefault("id", normalize(id))),
                String.valueOf(data.getOrDefault("model", "?")),
                String.valueOf(data.getOrDefault("conversation_id", ToolAudit.newConversationId())),
                messages,
                auditRecords,
                String.valueOf(data.getOrDefault("created_at", "?")),
                String.valueOf(data.getOrDefault("updated_at", "?"))
        );
    }

    public synchronized List<SessionInfo> list() throws IOException {
        if (!enabled || !Files.exists(sessionsDir)) return List.of();
        String active = activeId == null ? readActiveId() : activeId;
        try (var stream = Files.list(sessionsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> info(p, active))
                    .filter(i -> i != null)
                    .sorted(Comparator.comparing(SessionInfo::updatedAt).reversed())
                    .toList();
        }
    }

    public synchronized String activeId() {
        return enabled ? activeId : "";
    }

    private void write(Session session) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", session.id());
        data.put("model", session.model());
        data.put("conversation_id", session.conversationId());
        data.put("created_at", session.createdAt());
        data.put("updated_at", session.updatedAt());
        data.put("messages", session.messages());
        data.put("audit_records", session.auditRecords());
        JSON.writerWithDefaultPrettyPrinter().writeValue(path(session.id()).toFile(), data);
    }

    private SessionInfo info(Path file, String active) {
        try {
            Session session = load(file.getFileName().toString().replaceFirst("\\.json$", ""));
            return new SessionInfo(
                    session.id(),
                    session.model(),
                    session.createdAt(),
                    session.updatedAt(),
                    preview(session.messages()),
                    session.id().equals(active)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private String readActiveId() throws IOException {
        if (!Files.exists(stateFile)) return null;
        Map<String, Object> data = JSON.readValue(stateFile.toFile(), new TypeReference<>() {});
        Object id = data.get("active_session_id");
        return id == null || String.valueOf(id).isBlank() ? null : normalize(String.valueOf(id));
    }

    private void writeActiveId(String id) throws IOException {
        Files.createDirectories(dataDir);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active_session_id", id);
        JSON.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), data);
    }

    private Path path(String id) {
        Path p = sessionsDir.resolve(normalize(id) + ".json").toAbsolutePath().normalize();
        if (!p.getParent().equals(sessionsDir.toAbsolutePath().normalize())) throw new IllegalArgumentException("会话 ID 无效");
        return p;
    }

    private static List<Map<String, Object>> copyMessages(List<Map<String, Object>> messages) {
        return messages == null ? List.of() : messages.stream().map(LinkedHashMap::new).map(m -> (Map<String, Object>) m).toList();
    }

    private static String preview(List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            if (!"user".equals(message.get("role")) || message.get("content") == null) continue;
            String text = String.valueOf(message.get("content"));
            return text.length() > 80 ? text.substring(0, 80) : text;
        }
        return "";
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) {
            return "session_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        }
        String s = id.trim().replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        s = UNSAFE.matcher(s).replaceAll("-").replaceAll("^[._-]+|[._-]+$", "");
        return s.isBlank() ? normalize(null) : s;
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }

    private static Session disabledSession(String model) {
        return new Session("", model, ToolAudit.newConversationId(), List.of(), List.of(), now(), now());
    }
}
