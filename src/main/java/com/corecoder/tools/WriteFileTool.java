package com.corecoder.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class WriteFileTool extends ToolBase {
    @Override
    public String name() { return "Write"; }
    @Override
    public String description() { return "创建新文件，或完整覆盖已有文件。"; }
    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "file_path", prop("string", "要写入的文件路径"),
                "content", prop("string", "要写入的完整文件内容")
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
            return "已写入 " + lines + " 行到 " + file;
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }
}
