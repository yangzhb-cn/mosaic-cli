package com.yang.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(Files.readString(memory.memoryFile()).contains("MosaicCoder Memory"));
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
    void readMemoryReturnsContentOrEmptyForBlankAndMissingFiles() throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));

        assertEquals("", memory.readMemory());

        Files.createDirectories(memory.memoryFile().getParent());
        Files.writeString(memory.memoryFile(), "  \n  ");
        assertEquals("", memory.readMemory());

        Files.writeString(memory.memoryFile(), "important fact\n");
        assertEquals("important fact", memory.readMemory());
    }
}
