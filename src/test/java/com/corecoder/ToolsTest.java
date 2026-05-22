package com.corecoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolsTest {
    @TempDir
    Path temp;

    @Test
    void allToolsExposeSchemas() {
        List<Tools.Tool> tools = Tools.all(null);
        assertEquals(7, tools.size());
        for (Tools.Tool t : tools) {
            Map<String, Object> schema = t.schema();
            assertEquals("function", schema.get("type"));
            assertTrue(((Map<?, ?>) schema.get("function")).containsKey("parameters"));
        }
    }

    @Test
    void bashRunsCommandsAndBlocksDangerousCommands() {
        Tools.Tool bash = Tools.get(Tools.all(null), "bash");
        assertNotNull(bash);
        assertTrue(bash.execute(Map.of("command", "echo hello")).contains("hello"));
        assertTrue(bash.execute(Map.of("command", "rm -rf /")).contains("Blocked"));
    }

    @Test
    void readWriteAndEditFiles() throws Exception {
        List<Tools.Tool> tools = Tools.all(null);
        Path file = temp.resolve("sample.txt");

        assertTrue(Tools.get(tools, "write_file").execute(Map.of(
                "file_path", file.toString(),
                "content", "alpha\nbeta\n"
        )).contains("Wrote"));

        assertTrue(Tools.get(tools, "read_file").execute(Map.of("file_path", file.toString())).contains("1\talpha"));

        String edited = Tools.get(tools, "edit_file").execute(Map.of(
                "file_path", file.toString(),
                "old_string", "beta",
                "new_string", "gamma"
        ));
        assertTrue(edited.contains("Edited"));
        assertTrue(edited.contains("---"));
        assertEquals("alpha\ngamma\n", Files.readString(file));
        assertTrue(Tools.changedFiles().contains(file.toAbsolutePath().normalize().toString()));
    }

    @Test
    void editRequiresUniqueMatch() throws Exception {
        Path file = temp.resolve("dup.txt");
        Files.writeString(file, "x\nx\n");
        String out = Tools.get(Tools.all(null), "edit_file").execute(Map.of(
                "file_path", file.toString(),
                "old_string", "x",
                "new_string", "y"
        ));
        assertTrue(out.contains("2 times"));
    }

    @Test
    void globAndGrepFindFiles() throws Exception {
        Path file = temp.resolve("note.txt");
        Files.writeString(file, "hello needle\n");
        assertTrue(Tools.get(Tools.all(null), "glob").execute(Map.of(
                "pattern", "*.txt",
                "path", temp.toString()
        )).contains("note.txt"));
        assertTrue(Tools.get(Tools.all(null), "grep").execute(Map.of(
                "pattern", "needle",
                "path", temp.toString()
        )).contains("note.txt"));
    }

    @Test
    void agentToolNeedsParentAgent() {
        assertTrue(Tools.get(Tools.all(null), "agent").execute(Map.of("task", "x")).contains("not initialized"));
    }
}
