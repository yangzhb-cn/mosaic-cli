package com.coder.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// 写入/覆盖文件，并记录 changed file
public final class WriteFileTool extends ToolBase {
    @Override
    public String name() { return "Write"; }
    @Override
    public String description() {
        return """
                向本地文件系统写入文件。

                使用：
                - 如果所提供路径已有文件，此工具会覆盖它。
                - 如果这是已有文件，你必须先使用 Read 工具读取文件内容。
                - 始终优先编辑代码库中的已有文件。除非明确需要，绝不要写新文件。
                - 绝不要主动创建文档文件（*.md）或 README 文件。只有在用户明确要求时才创建文档文件。
                """.strip();
    }
    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "file_path", prop("string", "要写入的文件绝对路径（必须是绝对路径）"),
                "content", prop("string", "要写入文件的内容")
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
