package com.yang.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 将每轮用户和助手最终回复追加到 workspace/conversations 的按日 Markdown 归档。 */
final class ConversationArchive {
    private final Path conversationsDir;

    ConversationArchive(Path conversationsDir) {
        this.conversationsDir = conversationsDir;
    }

    void archiveExchange(String userMessage, String assistantResponse) {
        try {
            Files.createDirectories(conversationsDir);
            LocalDate today = LocalDate.now();
            Path file = conversationsDir.resolve(today + ".md");
            String header = Files.exists(file) ? "" : "# Conversations - " + today + "\n";
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String entry = """

                    ## %s

                    **User**: %s

                    **Assistant**: %s

                    ---
                    """.formatted(time, clean(userMessage), clean(assistantResponse));
            Files.writeString(file, header + entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip();
    }
}
