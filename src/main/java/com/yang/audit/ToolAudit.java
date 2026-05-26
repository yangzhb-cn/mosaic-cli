package com.yang.audit;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ToolAudit {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");

    private final Path dir;
    private final Map<String, Stat> stats = new LinkedHashMap<>();
    private String conversationId;

    public ToolAudit() {
        this(defaultDir());
    }

    public ToolAudit(Path dir) {
        this(dir, newConversationId());
    }

    public ToolAudit(Path dir, String conversationId) {
        this.dir = dir;
        this.conversationId = normalize(conversationId);
    }

    public static String newConversationId() {
        return "conversation_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public synchronized String conversationId() {
        return conversationId;
    }

    public synchronized void reset() {
        stats.clear();
        conversationId = newConversationId();
    }

    public synchronized void restoreConversation(String id) {
        stats.clear();
        conversationId = normalize(id);
    }

    public synchronized void record(String tool, boolean success, long elapsedNanos) {
        Stat stat = stats.computeIfAbsent(tool == null || tool.isBlank() ? "unknown" : tool, ignored -> new Stat());
        stat.calls++;
        if (success) stat.success++;
        stat.totalNanos += Math.max(0, elapsedNanos);
    }

    public synchronized boolean isEmpty() {
        return stats.isEmpty();
    }

    public synchronized String table() {
        if (stats.isEmpty()) return "📊 当前对话还没有工具调用。";

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%-20s %7s %8s %13s %8s%n",
                "Tool", "Calls", "Success", "Success_Rate", "Avg_ms"));
        for (Map<String, Object> row : recordsLocked()) {
            out.append(String.format(Locale.ROOT, "%-20s %7d %8d %13s %8.1f%n",
                    row.get("Tool"),
                    row.get("Calls"),
                    row.get("Success"),
                    row.get("Success_Rate"),
                    row.get("Avg_ms")));
        }
        return out.toString().stripTrailing();
    }

    public synchronized Path save() throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("audit_" + conversationId + ".jsonl").toAbsolutePath().normalize();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("time", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        snapshot.put("records", recordsLocked());
        String line = JSON.writeValueAsString(snapshot) + System.lineSeparator();
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return file;
    }

    public synchronized List<Map<String, Object>> records() {
        return recordsLocked();
    }

    private List<Map<String, Object>> recordsLocked() {
        return stats.entrySet().stream()
                .map(e -> {
                    Stat stat = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("Tool", e.getKey());
                    row.put("Calls", stat.calls);
                    row.put("Success", stat.success);
                    row.put("Success_Rate", String.format(Locale.ROOT, "%.2f%%", stat.successRate()));
                    row.put("Avg_ms", round1(stat.avgMs()));
                    return row;
                })
                .toList();
    }

    private static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".mosaiccoder", "audits");
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) return newConversationId();
        String s = id.trim().replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        s = UNSAFE.matcher(s).replaceAll("-").replaceAll("^[._-]+|[._-]+$", "");
        return s.isBlank() ? newConversationId() : s;
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static final class Stat {
        private int calls;
        private int success;
        private long totalNanos;

        private double successRate() {
            return calls == 0 ? 0 : success * 100d / calls;
        }

        private double avgMs() {
            return calls == 0 ? 0 : totalNanos / 1_000_000d / calls;
        }
    }
}
