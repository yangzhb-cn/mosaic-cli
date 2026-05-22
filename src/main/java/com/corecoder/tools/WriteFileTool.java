package com.corecoder.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class WriteFileTool extends ToolBase {
    @Override
    public String name() { return "write_file"; }
    @Override
    public String description() { return "Create a new file or completely overwrite an existing one."; }
    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "file_path", prop("string", "Path for the file"),
                "content", prop("string", "Full file content to write")
        ), "file_path", "content");
    }
    @Override
    public String execute(Map<String, Object> args) {
        try {
            String file = str(args, "file_path", "");
            String content = str(args, "content", "");
            Path p = path(file);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content);
            Tools.markChanged(p);
            long lines = content.chars().filter(ch -> ch == '\n').count() + (content.isEmpty() || content.endsWith("\n") ? 0 : 1);
            return "Wrote " + lines + " lines to " + file;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
