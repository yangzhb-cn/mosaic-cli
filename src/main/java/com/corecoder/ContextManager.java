package com.corecoder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ContextManager {
    public final int maxTokens;
    private final int snipAt;
    private final int summarizeAt;
    private final int collapseAt;

    public ContextManager(int maxTokens) {
        this.maxTokens = maxTokens;
        this.snipAt = (int) (maxTokens * 0.50);
        this.summarizeAt = (int) (maxTokens * 0.70);
        this.collapseAt = (int) (maxTokens * 0.90);
    }

    public static int estimateTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> m : messages) {
            Object content = m.get("content");
            if (content != null) total += String.valueOf(content).length() / 3;
            Object tools = m.get("tool_calls");
            if (tools != null) total += String.valueOf(tools).length() / 3;
        }
        return total;
    }

    public boolean maybeCompress(List<Map<String, Object>> messages, LlmClient llm) {
        int current = estimateTokens(messages);
        boolean changed = false;
        if (current > snipAt && snipToolOutputs(messages)) {
            changed = true;
            current = estimateTokens(messages);
        }
        if (current > summarizeAt && messages.size() > 10 && summarizeOld(messages, llm, 8)) {
            changed = true;
            current = estimateTokens(messages);
        }
        if (current > collapseAt && messages.size() > 4) {
            hardCollapse(messages, llm);
            changed = true;
        }
        return changed;
    }

    private boolean snipToolOutputs(List<Map<String, Object>> messages) {
        boolean changed = false;
        for (Map<String, Object> m : messages) {
            if (!"tool".equals(m.get("role"))) continue;
            String content = String.valueOf(m.getOrDefault("content", ""));
            if (content.length() <= 1500) continue;
            String[] lines = content.split("\\R");
            if (lines.length <= 6) continue;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 3; i++) s.append(lines[i]).append('\n');
            s.append("... (").append(lines.length).append(" lines, snipped to save context) ...\n");
            for (int i = Math.max(3, lines.length - 3); i < lines.length; i++) s.append(lines[i]).append('\n');
            m.put("content", s.toString().stripTrailing());
            changed = true;
        }
        return changed;
    }

    private boolean summarizeOld(List<Map<String, Object>> messages, LlmClient llm, int keepRecent) {
        if (messages.size() <= keepRecent) return false;
        List<Map<String, Object>> old = new ArrayList<>(messages.subList(0, messages.size() - keepRecent));
        List<Map<String, Object>> tail = new ArrayList<>(messages.subList(messages.size() - keepRecent, messages.size()));
        messages.clear();
        messages.add(Map.of("role", "user", "content", "[Context compressed - conversation summary]\n" + summary(old, llm)));
        messages.add(Map.of("role", "assistant", "content", "Got it, I have the context from our earlier conversation."));
        messages.addAll(tail);
        return true;
    }

    private void hardCollapse(List<Map<String, Object>> messages, LlmClient llm) {
        int keep = messages.size() > 4 ? 4 : 2;
        List<Map<String, Object>> old = new ArrayList<>(messages.subList(0, messages.size() - keep));
        List<Map<String, Object>> tail = new ArrayList<>(messages.subList(messages.size() - keep, messages.size()));
        messages.clear();
        messages.add(Map.of("role", "user", "content", "[Hard context reset]\n" + summary(old, llm)));
        messages.add(Map.of("role", "assistant", "content", "Context restored. Continuing from where we left off."));
        messages.addAll(tail);
    }

    private String summary(List<Map<String, Object>> messages, LlmClient llm) {
        if (llm != null) {
            try {
                LlmClient.Response r = llm.chat(List.of(
                        Map.of("role", "system", "content", "Compress this conversation into a brief summary. Preserve file paths, decisions, errors, and current task state."),
                        Map.of("role", "user", "content", flatten(messages, 15000))
                ), null, null);
                if (!r.content().isBlank()) return r.content();
            } catch (Exception ignored) {
            }
        }
        return extract(messages);
    }

    private static String flatten(List<Map<String, Object>> messages, int limit) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> m : messages) {
            out.append('[').append(m.getOrDefault("role", "?")).append("] ");
            out.append(String.valueOf(m.getOrDefault("content", "")), 0, Math.min(400, String.valueOf(m.getOrDefault("content", "")).length()));
            out.append('\n');
            if (out.length() >= limit) break;
        }
        return out.substring(0, Math.min(out.length(), limit));
    }

    private static String extract(List<Map<String, Object>> messages) {
        Pattern file = Pattern.compile("[\\w./-]+\\.\\w{1,5}");
        LinkedHashSet<String> files = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            String text = String.valueOf(m.getOrDefault("content", ""));
            file.matcher(text).results().limit(20).forEach(r -> files.add(r.group()));
            for (String line : text.split("\\R")) {
                if (line.toLowerCase().contains("error")) errors.add(line.strip());
                if (errors.size() >= 5) break;
            }
        }
        List<String> parts = new ArrayList<>();
        if (!files.isEmpty()) parts.add("Files touched: " + String.join(", ", files));
        if (!errors.isEmpty()) parts.add("Errors seen: " + String.join("; ", errors));
        return parts.isEmpty() ? "(no extractable context)" : String.join("\n", parts);
    }
}
