package com.coder.tools;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

// 	用正则搜索文件内容，返回 file:line 匹配
public final class GrepTool extends ToolBase {
    private static final Set<String> SKIP = Set.of(".git", "node_modules", "__pycache__", ".venv", "venv", ".tox", "dist", "build", "target");

    @Override
    public String name() { return "Grep"; }

    @Override
    public String description() {
        return """
                - 快速内容搜索工具，适用于任意规模代码库
                - 使用正则表达式搜索文件内容
                - 支持完整 regex 语法（例如 "log.*Error"、"function\\s+\\w+" 等）
                - 可通过 include 参数按文件模式过滤（例如 "*.js"、"*.{ts,tsx}"）
                - 返回匹配位置和匹配行，最多返回 200 条
                - 当你需要搜索文件内容时使用此工具
                - 如果需要识别/统计文件内匹配数量，使用 Bash 工具直接运行 `rg`（ripgrep）。不要使用 `grep`。
                - 当你进行开放式搜索且可能需要多轮 glob 和 grep 时，改用 Agent 工具
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "pattern", prop("string", "用于搜索文件内容的正则表达式 pattern"),
                "path", prop("string", "要搜索的文件或目录。默认为当前工作目录。"),
                "include", prop("string", "搜索时包含的文件 pattern（例如 \"*.js\"、\"*.{ts,tsx}\"）")
        ), "pattern");
    }

    @Override
    public String execute(Map<String, Object> args) {
        Pattern regex;
        try {
            regex = Pattern.compile(str(args, "pattern", ""));
        } catch (PatternSyntaxException e) {
            return "正则表达式无效: " + e.getMessage();
        }
        try {
            Path base = path(str(args, "path", "."));
            if (!Files.exists(base)) return "错误: 未找到 " + base;
            List<Path> files = Files.isRegularFile(base) ? List.of(base) : walk(base, str(args, "include", null));
            List<String> matches = new ArrayList<>();
            for (Path fp : files) {
                List<String> lines;
                try { lines = Files.readString(fp).lines().toList(); } catch (Exception ignored) { continue; }
                for (int i = 0; i < lines.size(); i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        matches.add(fp + ":" + (i + 1) + ": " + lines.get(i).stripTrailing());
                        if (matches.size() >= 200) {
                            matches.add("... (已达到 200 条匹配上限)");
                            return String.join("\n", matches);
                        }
                    }
                }
            }
            return matches.isEmpty() ? "未找到匹配。" : String.join("\n", matches);
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }

    private List<Path> walk(Path root, String include) throws Exception {
        PathMatcher matcher = include == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + include);
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.iterator().hasNext())
                    .filter(p -> {
                        for (Path part : p) if (SKIP.contains(part.toString())) return false;
                        return matcher == null || matcher.matches(p.getFileName()) || matcher.matches(root.relativize(p));
                    })
                    .limit(5000)
                    .toList();
        }
    }
}
