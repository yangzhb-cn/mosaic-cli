package com.corecoder.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ReadFileTool extends ToolBase {
    @Override
    public String name() { return "Read"; }

    @Override
    public String description() {
        return """
                从本地文件系统读取文件。你可以使用此工具直接访问任何文件。假设此工具能够读取机器上的所有文本文件。如果用户提供了文件路径，假设该路径有效。读取不存在的文件也可以；会返回错误。

                使用：
                - file_path 参数必须是绝对路径，不能是相对路径
                - 默认情况下，从文件开头读取最多 2000 行
                - 可以选择指定 line offset 和 limit（尤其适用于长文件），但建议不提供这些参数来读取整个文件
                - 结果带行号返回，行号从 1 开始
                - 你可以在一次回复中调用多个工具。推测性地批量读取多个可能有用的文件通常更好。
                - 如果你读取了存在但内容为空的文件，会返回空文件提示。
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "file_path", prop("string", "要读取文件的绝对路径"),
                "offset", prop("integer", "开始读取的行号。仅当文件太大不能一次读取时提供。"),
                "limit", prop("integer", "要读取的行数。仅当文件太大不能一次读取时提供。")
        ), "file_path");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String file = str(args, "file_path", "");
            Path p = path(file);
            if (!Files.exists(p)) return "错误: 未找到文件 " + file;
            if (!Files.isRegularFile(p)) return "错误: " + file + " 是目录，不是文件";
            List<String> lines = Files.readString(p).isEmpty() ? List.of() : Files.readString(p).lines().toList();
            int start = Math.max(0, integer(args, "offset", 1) - 1);
            int limit = integer(args, "limit", 2000);
            StringBuilder out = new StringBuilder();
            for (int i = start; i < Math.min(lines.size(), start + limit); i++) out.append(i + 1).append('\t').append(lines.get(i)).append('\n');
            if (lines.size() > start + limit) out.append("... (共 ").append(lines.size()).append(" 行，显示 ").append(start + 1).append('-').append(Math.min(lines.size(), start + limit)).append(')');
            return out.isEmpty() ? "(空文件)" : out.toString().stripTrailing();
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }
}
