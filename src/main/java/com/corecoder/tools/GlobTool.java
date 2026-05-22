package com.corecoder.tools;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class GlobTool extends ToolBase {
    @Override
    public String name() { return "Glob"; }

    @Override
    public String description() { return "按 glob 模式快速查找文件。"; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "pattern", prop("string", "glob 模式，例如 '**/*.py'"),
                "path", prop("string", "搜索目录，默认当前工作目录")
        ), "pattern");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String pattern = str(args, "pattern", "");
            Path base = path(str(args, "path", "."));
            if (!Files.isDirectory(base)) return "Error: " + base + " is not a directory";
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> hits;
            try (var walk = Files.walk(base)) {
                hits = walk.filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(base.relativize(p)) || matcher.matches(p.getFileName()))
                        .sorted(Comparator.comparingLong(this::mtime).reversed())
                        .limit(101)
                        .toList();
            }
            if (hits.isEmpty()) return "No files matched.";
            String out = String.join("\n", hits.stream().limit(100).map(Path::toString).toList());
            if (hits.size() > 100) out += "\n... (more than 100 matches, showing first 100)";
            return out;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0; }
    }
}
