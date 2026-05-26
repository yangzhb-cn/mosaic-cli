package com.yang.memory;

import com.yang.context.ContextManager;
import com.yang.llm.LlmClient;
import com.yang.prompt.Prompt;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 用一次 LLM 调用把当前 session 提炼成完整的新 Mosaic.md 内容。 */
final class MemoryUpdater {
    private static final int MAX_SESSION_CHARS = 20000;

    private MemoryUpdater() {
    }

    static String update(LlmClient llm, String currentMemory, List<Map<String, Object>> messages) throws IOException {
        if (llm == null) return "";
        String user = """
                当前 Mosaic.md：
                %s

                当前 session messages：
                %s
                """.formatted(blank(currentMemory), flatten(messages));
        LlmClient.Response response = llm.chat(List.of(
                Map.of("role", "system", "content", Prompt.memoryUpdatePrompt()),
                Map.of("role", "user", "content", user)
        ), List.of(), null, null);
        return stripFence(response.content()).strip();
    }

    private static String flatten(List<Map<String, Object>> messages) {
        StringBuilder out = new StringBuilder();
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                String role = String.valueOf(message.getOrDefault("role", ""));
                if (!role.equals("user") && !role.equals("assistant")) continue;
                String content = ContextManager.stripSystemReminder(String.valueOf(message.getOrDefault("content", ""))).strip();
                if (!content.isBlank()) out.append(role).append(": ").append(content).append("\n\n");
            }
        }
        String text = out.toString().strip();
        return text.length() <= MAX_SESSION_CHARS ? text : text.substring(text.length() - MAX_SESSION_CHARS);
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? "(empty)" : text.strip();
    }

    private static String stripFence(String text) {
        if (text == null) return "";
        String s = text.strip();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first >= 0 && last > first) return s.substring(first + 1, last);
        }
        return s;
    }
}
