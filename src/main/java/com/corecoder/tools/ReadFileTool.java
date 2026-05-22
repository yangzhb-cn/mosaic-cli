package com.corecoder.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ReadFileTool extends ToolBase {
    @Override
    public String name() { return "Read"; }

    @Override
    public String description() { return "读取文件内容，并带行号返回。"; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "file_path", prop("string", "要读取的文件路径"),
                "offset", prop("integer", "起始行号，从 1 开始，默认 1"),
                "limit", prop("integer", "最多读取行数，默认 2000")
        ), "file_path");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String file = str(args, "file_path", "");
            Path p = path(file);
            if (!Files.exists(p)) return "Error: " + file + " not found";
            if (!Files.isRegularFile(p)) return "Error: " + file + " is a directory, not a file";
            List<String> lines = Files.readString(p).isEmpty() ? List.of() : Files.readString(p).lines().toList();
            int start = Math.max(0, integer(args, "offset", 1) - 1);
            int limit = integer(args, "limit", 2000);
            StringBuilder out = new StringBuilder();
            for (int i = start; i < Math.min(lines.size(), start + limit); i++) out.append(i + 1).append('\t').append(lines.get(i)).append('\n');
            if (lines.size() > start + limit) out.append("... (").append(lines.size()).append(" lines total, showing ").append(start + 1).append('-').append(Math.min(lines.size(), start + limit)).append(')');
            return out.isEmpty() ? "(empty file)" : out.toString().stripTrailing();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
