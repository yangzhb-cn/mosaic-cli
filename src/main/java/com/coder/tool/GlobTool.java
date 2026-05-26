package com.coder.tool;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

// 	按 glob pattern 找文件，按修改时间排序
public final class GlobTool extends ToolBase {
    @Override
    public String name() { return "Glob"; }

    @Override
    public String description() {
        return """
                - 快速文件模式匹配工具，适用于任意规模代码库
                - 支持类似 "**/*.js" 或 "src/**/*.ts" 的 glob patterns
                - 返回按修改时间排序的匹配文件路径
                - 当你需要按文件名模式查找文件时使用此工具
                - 当你进行开放式搜索且可能需要多轮 glob 和 grep 时，改用 Agent 工具
                - 你可以在一次回复中调用多个工具。推测性地批量执行多个可能有用的搜索通常更好。
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "pattern", prop("string", "用于匹配文件的 glob pattern"),
                "path", prop("string", "要搜索的目录。如果未指定，将使用当前工作目录。重要：省略此字段以使用默认目录。不要输入 \"undefined\" 或 \"null\"，直接省略即可。如果提供，必须是有效目录路径。")
        ), "pattern");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String pattern = str(args, "pattern", "");
            Path base = path(str(args, "path", "."));
            if (!Files.isDirectory(base)) return "错误: " + base + " 不是目录";
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> hits;
            try (var walk = Files.walk(base)) {
                hits = walk.filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(base.relativize(p)) || matcher.matches(p.getFileName()))
                        .sorted(Comparator.comparingLong(this::mtime).reversed())
                        .limit(101)
                        .toList();
            }
            if (hits.isEmpty()) return "没有匹配的文件。";
            String out = String.join("\n", hits.stream().limit(100).map(Path::toString).toList());
            if (hits.size() > 100) out += "\n... (超过 100 个匹配，仅显示前 100 个)";
            return out;
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }

    private long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0; }
    }
}
