package com.yang.memory;

import com.yang.prompt.Prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 管理 workspace/CLAUDE.md 长期记忆和 conversations 目录。 */
public final class MemoryManager {
    private final Path workspaceDir;
    private final boolean enabled;

    private MemoryManager(Path workspaceDir, boolean enabled) {
        this.workspaceDir = workspaceDir == null ? null : workspaceDir.toAbsolutePath().normalize();
        this.enabled = enabled;
    }

    public static MemoryManager forWorkspace(Path workspaceDir) {
        return new MemoryManager(workspaceDir, true);
    }

    public static MemoryManager disabled() {
        return new MemoryManager(null, false);
    }

    public void ensureWorkspace() throws IOException {
        if (!enabled) return;
        Files.createDirectories(workspaceDir);
        Files.createDirectories(conversationsDir());
        Path memory = memoryFile();
        if (!Files.exists(memory)) Files.writeString(memory, Prompt.memoryTemplate(), StandardCharsets.UTF_8);
    }

    public String readMemory() {
        if (!enabled) return "";
        try {
            String text = Files.exists(memoryFile()) ? Files.readString(memoryFile(), StandardCharsets.UTF_8).strip() : "";
            return text.isBlank() ? "" : text;
        } catch (Exception ignored) {
            return "";
        }
    }

    public Path memoryFile() {
        if (!enabled) throw new IllegalStateException("Memory is disabled");
        return workspaceDir.resolve("CLAUDE.md");
    }

    public Path conversationsDir() {
        if (!enabled) throw new IllegalStateException("Memory is disabled");
        return workspaceDir.resolve("conversations");
    }
}
