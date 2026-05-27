package com.yang.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yang.audit.ToolAudit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 管理项目本地 data/state.json 和 data/sessions 下的 JSONL 多会话。 */
public final class SessionManager {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter LEGACY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path dataDir;
    private final Path sessionsDir;
    private final Path stateFile;
    private final boolean enabled;
    private String activeId;
    private Path activePath;
    private List<Map<String, Object>> activeMessages = List.of();

    /** 加载出来的当前可恢复 session。 */
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
        ensureReady();
        State state = readState();
        activeId = state.id();
        activePath = state.path();
        if (activePath == null && activeId != null) activePath = findPath(activeId);
        Session session = activePath == null ? null : replay(activePath);
        if (session != null) {
            activeId = session.id();
            activeMessages = copyMessages(session.messages());
            writeActiveId(activeId, activePath);
            return session;
        }
        return create(null, model);
    }

    public synchronized Session create(String id, String model) throws IOException {
        if (!enabled) return disabledSession(model);
        ensureReady();
        String sid = normalize(id);
        if (findPath(sid) != null) throw new IllegalArgumentException("会话已存在: " + sid);
        activePath = newSessionPath(sid);
        activeId = sid;
        activeMessages = List.of();
        Files.createDirectories(activePath.getParent());
        String now = now();
        String conversationId = ToolAudit.newConversationId();
        append(activePath, "session_meta", Map.of(
                "id", sid,
                "conversation_id", conversationId,
                "model", model == null ? "" : model,
                "cwd", Path.of("").toAbsolutePath().toString(),
                "created_at", now
        ));
        writeActiveId(sid, activePath);
        return new Session(sid, model, conversationId, List.of(), List.of(), now, now);
    }

    public synchronized Session switchTo(String id) throws IOException {
        if (!enabled) return null;
        ensureReady();
        Path path = findPath(id);
        if (path == null) return null;
        Session session = replay(path);
        activeId = session.id();
        activePath = path;
        activeMessages = copyMessages(session.messages());
        writeActiveId(activeId, activePath);
        return session;
    }

    public synchronized void saveActive(List<Map<String, Object>> messages, String model, String conversationId) throws IOException {
        saveActive(messages, model, conversationId, List.of());
    }

    public synchronized void saveActive(List<Map<String, Object>> messages, String model, String conversationId, List<Map<String, Object>> auditRecords) throws IOException {
        if (!enabled) return;
        ensureActive(model);
        List<Map<String, Object>> current = copyMessages(messages);
        append(activePath, "turn_context", Map.of(
                "cwd", Path.of("").toAbsolutePath().toString(),
                "model", model == null ? "" : model,
                "conversation_id", conversationId == null ? "" : conversationId
        ));
        if (startsWith(current, activeMessages)) {
            for (int i = activeMessages.size(); i < current.size(); i++) appendMessage(current.get(i));
        } else {
            append(activePath, "context_reset", Map.of(
                    "conversation_id", conversationId == null ? "" : conversationId,
                    "reason", "messages_changed"
            ));
            for (Map<String, Object> message : current) appendMessage(message);
        }
        appendAudit(auditRecords);
        activeMessages = current;
        writeActiveId(activeId, activePath);
    }

    public synchronized void resetActive(String conversationId, List<Map<String, Object>> auditRecords) throws IOException {
        if (!enabled) return;
        ensureActive("");
        append(activePath, "session_reset", Map.of(
                "conversation_id", conversationId == null ? "" : conversationId
        ));
        appendAudit(auditRecords);
        activeMessages = List.of();
        writeActiveId(activeId, activePath);
    }

    public synchronized void recordEvent(String kind, Map<String, Object> payload) {
        if (!enabled) return;
        try {
            ensureActive("");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", kind == null ? "event" : kind);
            if (payload != null) data.putAll(payload);
            append(activePath, "event_msg", data);
        } catch (Exception ignored) {
        }
    }

    public synchronized Session load(String id) throws IOException {
        if (!enabled) return null;
        ensureReady();
        Path path = findPath(id);
        return path == null ? null : replay(path);
    }

    public synchronized List<SessionInfo> list() throws IOException {
        if (!enabled) return List.of();
        ensureReady();
        String active = activeId == null ? readState().id() : activeId;
        try (var stream = Files.walk(sessionsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .map(this::info)
                    .filter(i -> i != null)
                    .map(i -> new SessionInfo(i.id(), i.model(), i.createdAt(), i.updatedAt(), i.preview(), i.id().equals(active)))
                    .sorted(Comparator.comparing(SessionInfo::updatedAt).reversed())
                    .toList();
        }
    }

    public synchronized String activeId() {
        return enabled ? activeId : "";
    }

    private void ensureReady() throws IOException {
        Files.createDirectories(sessionsDir);
        migrateLegacySessions();
    }

    private void ensureActive(String model) throws IOException {
        ensureReady();
        if (activePath != null && Files.exists(activePath)) return;
        State state = readState();
        activeId = state.id();
        activePath = state.path();
        if (activePath == null && activeId != null) activePath = findPath(activeId);
        if (activePath == null || !Files.exists(activePath)) create(null, model);
    }

    private void appendMessage(Map<String, Object> message) throws IOException {
        append(activePath, "response_item", Map.of("message", new LinkedHashMap<>(message)));
    }

    private void appendAudit(List<Map<String, Object>> auditRecords) throws IOException {
        append(activePath, "audit_snapshot", Map.of("records", copyMessages(auditRecords)));
    }

    private void append(Path path, String type, Map<String, Object> payload) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", now());
        event.put("type", type);
        event.put("payload", payload == null ? Map.of() : payload);
        Files.createDirectories(path.getParent());
        Files.writeString(path, JSON.writeValueAsString(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private Session replay(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        String id = idFromFile(file);
        String model = "?";
        String conversationId = ToolAudit.newConversationId();
        String createdAt = now();
        String updatedAt = createdAt;
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, Object>> auditRecords = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Map<String, Object> event = JSON.readValue(line, new TypeReference<>() {});
            String type = String.valueOf(event.getOrDefault("type", ""));
            updatedAt = String.valueOf(event.getOrDefault("timestamp", updatedAt));
            Map<String, Object> payload = asMap(event.get("payload"));
            switch (type) {
                case "session_meta" -> {
                    id = String.valueOf(payload.getOrDefault("id", id));
                    model = String.valueOf(payload.getOrDefault("model", model));
                    conversationId = String.valueOf(payload.getOrDefault("conversation_id", conversationId));
                    createdAt = String.valueOf(payload.getOrDefault("created_at", updatedAt));
                }
                case "response_item" -> {
                    Map<String, Object> message = asMap(payload.get("message"));
                    if (!message.isEmpty()) messages.add(message);
                }
                case "turn_context" -> {
                    Object cid = payload.get("conversation_id");
                    if (cid != null && !String.valueOf(cid).isBlank()) conversationId = String.valueOf(cid);
                }
                case "audit_snapshot" -> auditRecords = copyMessages(asListOfMaps(payload.get("records")));
                case "session_reset" -> {
                    messages.clear();
                    auditRecords.clear();
                    conversationId = String.valueOf(payload.getOrDefault("conversation_id", ToolAudit.newConversationId()));
                }
                case "context_reset" -> {
                    messages.clear();
                    Object cid = payload.get("conversation_id");
                    if (cid != null && !String.valueOf(cid).isBlank()) conversationId = String.valueOf(cid);
                }
                default -> {
                }
            }
        }
        return new Session(id, model, conversationId, messages, auditRecords, createdAt, updatedAt);
    }

    private SessionInfo info(Path file) {
        try {
            Session session = replay(file);
            return session == null ? null : new SessionInfo(
                    session.id(),
                    session.model(),
                    session.createdAt(),
                    session.updatedAt(),
                    preview(session.messages()),
                    false
            );
        } catch (Exception e) {
            return null;
        }
    }

    private Path findPath(String id) throws IOException {
        String sid = normalize(id);
        if (!Files.exists(sessionsDir)) return null;
        try (var stream = Files.walk(sessionsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(p -> sid.equals(idFromFile(p)) || sid.equals(metaId(p)))
                    .findFirst()
                    .orElse(null);
        }
    }

    private String metaId(Path file) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Map<String, Object> event = JSON.readValue(line, new TypeReference<>() {});
                if (!"session_meta".equals(event.get("type"))) continue;
                return String.valueOf(asMap(event.get("payload")).getOrDefault("id", ""));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void migrateLegacySessions() throws IOException {
        if (!Files.exists(sessionsDir)) return;
        try (var stream = Files.list(sessionsDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                migrateLegacy(file);
            }
        }
    }

    private void migrateLegacy(Path file) {
        try {
            Map<String, Object> data = JSON.readValue(file.toFile(), new TypeReference<>() {});
            String id = normalize(String.valueOf(data.getOrDefault("id", file.getFileName().toString().replaceFirst("\\.json$", ""))));
            if (findPath(id) != null) return;
            String createdAt = String.valueOf(data.getOrDefault("created_at", now()));
            String updatedAt = String.valueOf(data.getOrDefault("updated_at", createdAt));
            String model = String.valueOf(data.getOrDefault("model", "?"));
            String conversationId = String.valueOf(data.getOrDefault("conversation_id", ToolAudit.newConversationId()));
            Path jsonl = legacyPath(id, createdAt);
            append(jsonl, "session_meta", Map.of(
                    "id", id,
                    "conversation_id", conversationId,
                    "model", model,
                    "cwd", Path.of("").toAbsolutePath().toString(),
                    "created_at", createdAt
            ));
            for (Map<String, Object> message : asListOfMaps(data.get("messages"))) {
                append(jsonl, "response_item", Map.of("message", message));
            }
            append(jsonl, "audit_snapshot", Map.of("records", asListOfMaps(data.get("audit_records"))));
            append(jsonl, "event_msg", Map.of("kind", "legacy_migrated", "source", file.toString(), "updated_at", updatedAt));
        } catch (Exception ignored) {
        }
    }

    private State readState() throws IOException {
        if (!Files.exists(stateFile)) return new State(null, null);
        Map<String, Object> data = JSON.readValue(stateFile.toFile(), new TypeReference<>() {});
        String id = data.get("active_session_id") == null ? null : normalize(String.valueOf(data.get("active_session_id")));
        Object rawPath = data.get("active_session_path");
        Path path = rawPath == null ? null : dataDir.resolve(String.valueOf(rawPath)).toAbsolutePath().normalize();
        if (path != null && !path.startsWith(dataDir)) path = null;
        return new State(id, path);
    }

    private void writeActiveId(String id, Path path) throws IOException {
        Files.createDirectories(dataDir);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active_session_id", id);
        data.put("active_session_path", dataDir.relativize(path.toAbsolutePath().normalize()).toString());
        JSON.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), data);
    }

    private Path newSessionPath(String id) {
        LocalDate today = LocalDate.now();
        String timestamp = LocalDateTime.now().format(FILE_TIME);
        return sessionsDir
                .resolve(String.format("%04d/%02d/%02d", today.getYear(), today.getMonthValue(), today.getDayOfMonth()))
                .resolve("session_" + timestamp + "_" + normalize(id) + ".jsonl")
                .toAbsolutePath()
                .normalize();
    }

    private Path legacyPath(String id, String createdAt) {
        LocalDate date = parseLegacyDate(createdAt);
        String timestamp = LocalDateTime.now().format(FILE_TIME);
        return sessionsDir
                .resolve(String.format("%04d/%02d/%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth()))
                .resolve("session_" + timestamp + "_" + normalize(id) + ".jsonl")
                .toAbsolutePath()
                .normalize();
    }

    private static LocalDate parseLegacyDate(String value) {
        try {
            return LocalDateTime.parse(value, LEGACY_TIME).toLocalDate();
        } catch (Exception ignored) {
            return LocalDate.now();
        }
    }

    private static List<Map<String, Object>> copyMessages(List<Map<String, Object>> messages) {
        if (messages == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> message : messages) out.add(new LinkedHashMap<>(message));
        return out;
    }

    private static boolean startsWith(List<Map<String, Object>> messages, List<Map<String, Object>> prefix) {
        if (messages.size() < prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!messages.get(i).equals(prefix.get(i))) return false;
        }
        return true;
    }

    private static String preview(List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            if (!"user".equals(message.get("role")) || message.get("content") == null) continue;
            String text = String.valueOf(message.get("content"));
            return text.length() > 80 ? text.substring(0, 80) : text;
        }
        return "";
    }

    private static String idFromFile(Path file) {
        String name = file.getFileName().toString();
        name = name.replaceFirst("\\.jsonl$", "");
        String[] parts = name.split("_", 5);
        return parts.length == 5 ? normalize(parts[4]) : normalize(name);
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) out.add(map);
        }
        return out;
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank() || "null".equals(id)) {
            return "session_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        }
        String s = id.trim().replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        s = UNSAFE.matcher(s).replaceAll("-").replaceAll("^[._-]+|[._-]+$", "");
        return s.isBlank() ? normalize(null) : s;
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static Session disabledSession(String model) {
        return new Session("", model, ToolAudit.newConversationId(), List.of(), List.of(), now(), now());
    }

    private record State(String id, Path path) {}
}
