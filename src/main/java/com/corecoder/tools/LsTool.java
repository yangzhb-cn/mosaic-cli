package com.corecoder.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class LsTool extends ToolBase {
    @Override
    public String name() { return "LS"; }

    @Override
    public String description() { return "列出绝对路径下的文件和目录。"; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "path", prop("string", "要列出的目录绝对路径"),
                "ignore", arrayProp("要忽略的文件匹配模式", prop("string", "忽略模式"))
        ), "path");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String raw = str(args, "path", "");
            if (!Path.of(raw).isAbsolute()) return "错误: path 必须是绝对路径";
            Path dir = path(raw);
            if (!dir.isAbsolute()) return "错误: path 必须是绝对路径";
            if (!Files.isDirectory(dir)) return "错误: " + dir + " 不是目录";
            List<String> ignore = list(args.get("ignore"));
            try (var stream = Files.list(dir)) {
                List<String> entries = stream
                        .filter(p -> !ignored(dir, p, ignore))
                        .sorted()
                        .map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : ""))
                        .toList();
                return entries.isEmpty() ? "(空目录)" : String.join("\n", entries);
            }
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }

    private boolean ignored(Path root, Path path, List<String> ignore) {
        Path name = path.getFileName();
        Path relative = root.relativize(path);
        for (String pattern : ignore) {
            var matcher = path.getFileSystem().getPathMatcher("glob:" + pattern);
            if (matcher.matches(name) || matcher.matches(relative)) return true;
        }
        return false;
    }
}
