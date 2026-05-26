package com.yang.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.yang.llm.LlmClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {
    @TempDir
    Path temp;

    @Test
    void ensureWorkspaceCreatesMemoryFileAndConversationsDir() throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));

        memory.ensureWorkspace();

        assertTrue(Files.isRegularFile(memory.memoryFile()));
        assertTrue(Files.isDirectory(memory.conversationsDir()));
        assertTrue(Files.readString(memory.memoryFile()).contains("MosaicCoder 长期记忆"));
    }

    @Test
    void ensureWorkspaceDoesNotOverwriteExistingMemory() throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));
        Files.createDirectories(memory.memoryFile().getParent());
        Files.writeString(memory.memoryFile(), "custom memory");

        memory.ensureWorkspace();

        assertEquals("custom memory", Files.readString(memory.memoryFile()));
        assertTrue(Files.isDirectory(memory.conversationsDir()));
    }

    @Test
    void ensureWorkspaceMigratesLegacyClaudeFile() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("CLAUDE.md"), "legacy memory");
        MemoryManager memory = MemoryManager.forWorkspace(workspace);

        memory.ensureWorkspace();

        assertEquals("legacy memory", Files.readString(memory.memoryFile()));
        assertFalse(Files.exists(workspace.resolve("CLAUDE.md")));
    }

    @Test
    void readMemoryReturnsContentOrEmptyForBlankAndMissingFiles() throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));

        assertEquals("", memory.readMemory());

        Files.createDirectories(memory.memoryFile().getParent());
        Files.writeString(memory.memoryFile(), "  \n  ");
        assertEquals("", memory.readMemory());

        Files.writeString(memory.memoryFile(), "important fact\n");
        assertEquals("important fact", memory.readMemory());
    }

    @Test
    void archiveExchangeAppendsMarkdownByDate() throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));
        memory.ensureWorkspace();

        memory.archiveExchange("hello", "answer");

        Path archive = memory.conversationsDir().resolve(java.time.LocalDate.now() + ".md");
        String text = Files.readString(archive);
        assertTrue(text.contains("# Conversations"));
        assertTrue(text.contains("**User**: hello"));
        assertTrue(text.contains("**Assistant**: answer"));
    }

    @Test
    void updateMemoryWritesNonEmptyLlmResultAndKeepsOldFileOnBlank() throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));
        memory.ensureWorkspace();
        Files.writeString(memory.memoryFile(), "old memory");

        assertTrue(memory.updateMemory(new MemoryFakeLlm("# New Memory", "old memory"), List.of(Map.of("role", "user", "content", "I like short answers"))));
        assertEquals("# New Memory", memory.readMemory());

        assertFalse(memory.updateMemory(new MemoryFakeLlm("   ", "# New Memory"), List.of(Map.of("role", "user", "content", "ignored"))));
        assertEquals("# New Memory", memory.readMemory());
    }

    private static final class MemoryFakeLlm extends LlmClient {
        private final String content;
        private final String expectedMemory;

        private MemoryFakeLlm(String content, String expectedMemory) {
            super("test-model", "key", "http://localhost", 0);
            this.content = content;
            this.expectedMemory = expectedMemory;
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            assertTrue(String.valueOf(messages.getFirst().get("content")).contains("长期记忆"));
            assertTrue(String.valueOf(messages.getLast().get("content")).contains(expectedMemory));
            return new Response(content, "", List.of(), 0, 0, 0);
        }
    }
}
