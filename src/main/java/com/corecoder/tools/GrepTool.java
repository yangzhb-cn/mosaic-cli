package com.corecoder.tools;

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

public final class GrepTool extends ToolBase {
    private static final Set<String> SKIP = Set.of(".git", "node_modules", "__pycache__", ".venv", "venv", ".tox", "dist", "build", "target");

    @Override
    public String name() { return "grep"; }

    @Override
    public String description() { return "Search file contents with regex."; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "pattern", prop("string", "Regex pattern to search for"),
                "path", prop("string", "File or directory to search (default: cwd)"),
                "include", prop("string", "Only search files matching this glob")
        ), "pattern");
    }

    @Override
    public String execute(Map<String, Object> args) {
        Pattern regex;
        try {
            regex = Pattern.compile(str(args, "pattern", ""));
        } catch (PatternSyntaxException e) {
            return "Invalid regex: " + e.getMessage();
        }
        try {
            Path base = path(str(args, "path", "."));
            if (!Files.exists(base)) return "Error: " + base + " not found";
            List<Path> files = Files.isRegularFile(base) ? List.of(base) : walk(base, str(args, "include", null));
            List<String> matches = new ArrayList<>();
            for (Path fp : files) {
                List<String> lines;
                try { lines = Files.readString(fp).lines().toList(); } catch (Exception ignored) { continue; }
                for (int i = 0; i < lines.size(); i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        matches.add(fp + ":" + (i + 1) + ": " + lines.get(i).stripTrailing());
                        if (matches.size() >= 200) {
                            matches.add("... (200 match limit reached)");
                            return String.join("\n", matches);
                        }
                    }
                }
            }
            return matches.isEmpty() ? "No matches found." : String.join("\n", matches);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
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
