package com.coder.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileBrowserTest {
    @TempDir
    Path temp;

    @Test
    void listsAndReadsFilesUnderAllowedRoot() throws Exception {
        Files.createDirectory(temp.resolve("src"));
        Files.writeString(temp.resolve("README.md"), "hello");
        FileBrowser browser = new FileBrowser(List.of(temp));

        Map<String, Object> listed = browser.list(temp.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) listed.get("entries");

        assertEquals("src", entries.get(0).get("name"));
        assertEquals("README.md", entries.get(1).get("name"));
        assertEquals("hello", browser.read(temp.resolve("README.md").toString()).get("content"));
    }

    @Test
    void rejectsPathsOutsideAllowedRoot() {
        FileBrowser browser = new FileBrowser(List.of(temp));

        assertFalse(browser.isAllowed(temp.resolve("../outside").normalize().toString()));
    }
}
