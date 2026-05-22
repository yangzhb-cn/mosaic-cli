package com.corecoder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Tools {
    private static final Set<String> CHANGED = new LinkedHashSet<>();

    public interface Tool {
        String name();
        String description();
        Map<String, Object> parameters();
        String execute(Map<String, Object> args);

        default Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of(
                    "name", name(),
                    "description", description(),
                    "parameters", parameters()
            ));
        }
    }

    public static List<Tool> all(Agent parent) {
        return List.of(
                new BashTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new GlobTool(),
                new GrepTool(),
                new AgentTool(parent)
        );
    }

    public static Tool get(List<Tool> tools, String name) {
        for (Tool t : tools) if (t.name().equals(name)) return t;
        return null;
    }

    public static Set<String> changedFiles() {
        return CHANGED;
    }

    private abstract static class BaseTool implements Tool {
        Map<String, Object> params(Map<String, Object> props, String... required) {
            return Map.of("type", "object", "properties", props, "required", List.of(required));
        }

        Map<String, Object> prop(String type, String description) {
            return Map.of("type", type, "description", description);
        }

        String str(Map<String, Object> args, String key, String def) {
            Object v = args.get(key);
            return v == null ? def : String.valueOf(v);
        }

        int integer(Map<String, Object> args, String key, int def) {
            Object v = args.get(key);
            if (v instanceof Number n) return n.intValue();
            if (v == null) return def;
            return Integer.parseInt(String.valueOf(v));
        }

        Path path(String raw) {
            if (raw.startsWith("~")) raw = System.getProperty("user.home") + raw.substring(1);
            return Path.of(raw).toAbsolutePath().normalize();
        }
    }

    private static class BashTool extends BaseTool {
        private static final String[][] DANGEROUS = {
                {"\\brm\\s+(-\\w*)?-r\\w*\\s+(/|~|\\$HOME)", "recursive delete on home/root"},
                {"\\brm\\s+(-\\w*)?-rf\\s", "force recursive delete"},
                {"\\bmkfs\\b", "format filesystem"},
                {"\\bdd\\s+.*of=/dev/", "raw disk write"},
                {">\\s*/dev/sd[a-z]", "overwrite block device"},
                {"\\bchmod\\s+(-R\\s+)?777\\s+/", "chmod 777 on root"},
                {":\\(\\)\\s*\\{.*:\\|:.*\\}", "fork bomb"},
                {"\\bcurl\\b.*\\|\\s*(sudo\\s+)?bash", "pipe curl to bash"},
                {"\\bwget\\b.*\\|\\s*(sudo\\s+)?bash", "pipe wget to bash"}
        };
        private static Path cwd;

        public String name() { return "bash"; }
        public String description() { return "Execute a shell command. Returns stdout, stderr, and exit code."; }
        public Map<String, Object> parameters() {
            return params(Map.of(
                    "command", prop("string", "The shell command to run"),
                    "timeout", prop("integer", "Timeout in seconds (default 120)")
            ), "command");
        }

        public String execute(Map<String, Object> args) {
            String command = str(args, "command", "");
            String blocked = dangerous(command);
            if (blocked != null) return "Blocked: " + blocked + "\nCommand: " + command;
            int timeout = integer(args, "timeout", 120);
            Path runDir = cwd == null ? Path.of("").toAbsolutePath() : cwd;
            List<String> shell = System.getProperty("os.name").toLowerCase().contains("win")
                    ? List.of("cmd", "/c", command)
                    : List.of("/bin/sh", "-c", command);
            try {
                Process p = new ProcessBuilder(shell).directory(runDir.toFile()).start();
                CompletableFuture<String> out = readAsync(p.getInputStream());
                CompletableFuture<String> err = readAsync(p.getErrorStream());
                if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return "Error: timed out after " + timeout + "s";
                }
                String text = out.get(1, TimeUnit.SECONDS);
                String stderr = err.get(1, TimeUnit.SECONDS);
                if (!stderr.isBlank()) text += "\n[stderr]\n" + stderr;
                if (p.exitValue() != 0) text += "\n[exit code: " + p.exitValue() + "]";
                if (p.exitValue() == 0) updateCwd(command, runDir);
                if (text.length() > 15000) text = text.substring(0, 6000) + "\n\n... truncated (" + text.length() + " chars total) ...\n\n" + text.substring(text.length() - 3000);
                return text.strip().isEmpty() ? "(no output)" : text.strip();
            } catch (Exception e) {
                return "Error running command: " + e.getMessage();
            }
        }

        private CompletableFuture<String> readAsync(InputStream in) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return "";
                }
            });
        }

        private String dangerous(String command) {
            for (String[] p : DANGEROUS) if (Pattern.compile(p[0]).matcher(command).find()) return p[1];
            return null;
        }

        private void updateCwd(String command, Path base) {
            Path cur = base;
            for (String part : command.split("&&")) {
                String s = part.strip();
                if (!s.startsWith("cd ")) continue;
                String raw = s.substring(3).strip().replaceAll("^['\"]|['\"]$", "");
                Path next = raw.startsWith("~")
                        ? Path.of(System.getProperty("user.home") + raw.substring(1)).toAbsolutePath().normalize()
                        : cur.resolve(raw).normalize();
                if (Files.isDirectory(next)) cur = next;
            }
            cwd = cur;
        }
    }

    private static class ReadFileTool extends BaseTool {
        public String name() { return "read_file"; }
        public String description() { return "Read a file's contents with line numbers."; }
        public Map<String, Object> parameters() {
            return params(Map.of(
                    "file_path", prop("string", "Path to the file"),
                    "offset", prop("integer", "Start line (1-based). Default 1."),
                    "limit", prop("integer", "Max lines to read. Default 2000.")
            ), "file_path");
        }

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

    private static class WriteFileTool extends BaseTool {
        public String name() { return "write_file"; }
        public String description() { return "Create a new file or completely overwrite an existing one."; }
        public Map<String, Object> parameters() {
            return params(Map.of(
                    "file_path", prop("string", "Path for the file"),
                    "content", prop("string", "Full file content to write")
            ), "file_path", "content");
        }

        public String execute(Map<String, Object> args) {
            try {
                String file = str(args, "file_path", "");
                String content = str(args, "content", "");
                Path p = path(file);
                Files.createDirectories(p.getParent());
                Files.writeString(p, content);
                CHANGED.add(p.toString());
                long lines = content.chars().filter(ch -> ch == '\n').count() + (content.isEmpty() || content.endsWith("\n") ? 0 : 1);
                return "Wrote " + lines + " lines to " + file;
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
    }

    private static class EditFileTool extends BaseTool {
        public String name() { return "edit_file"; }
        public String description() { return "Edit a file by replacing an exact unique string match."; }
        public Map<String, Object> parameters() {
            return params(Map.of(
                    "file_path", prop("string", "Path to the file to edit"),
                    "old_string", prop("string", "Exact text to find"),
                    "new_string", prop("string", "Replacement text")
            ), "file_path", "old_string", "new_string");
        }

        public String execute(Map<String, Object> args) {
            try {
                String file = str(args, "file_path", "");
                String oldString = str(args, "old_string", "");
                String newString = str(args, "new_string", "");
                Path p = path(file);
                if (!Files.exists(p)) return "Error: " + file + " not found";
                String old = Files.readString(p);
                int count = count(old, oldString);
                if (count == 0) return "Error: old_string not found in " + file + ".\nFile starts with:\n" + old.substring(0, Math.min(500, old.length()));
                if (count > 1) return "Error: old_string appears " + count + " times in " + file + ". Include more surrounding lines to make it unique.";
                int at = old.indexOf(oldString);
                String next = old.substring(0, at) + newString + old.substring(at + oldString.length());
                Files.writeString(p, next);
                CHANGED.add(p.toString());
                return "Edited " + file + "\n" + diff(old, next, p.toString());
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        private int count(String text, String needle) {
            if (needle.isEmpty()) return 0;
            int n = 0;
            for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
            return n;
        }

        private String diff(String old, String next, String file) {
            List<String> a = old.lines().toList();
            List<String> b = next.lines().toList();
            StringBuilder out = new StringBuilder("--- a/").append(file).append('\n').append("+++ b/").append(file).append('\n').append("@@\n");
            int max = Math.max(a.size(), b.size());
            for (int i = 0; i < max; i++) {
                String left = i < a.size() ? a.get(i) : null;
                String right = i < b.size() ? b.get(i) : null;
                if (left != null && left.equals(right)) out.append(' ').append(left).append('\n');
                else {
                    if (left != null) out.append('-').append(left).append('\n');
                    if (right != null) out.append('+').append(right).append('\n');
                }
                if (out.length() > 3000) return out.substring(0, 2500) + "\n... (diff truncated)\n";
            }
            return out.toString().stripTrailing();
        }
    }

    private static class GlobTool extends BaseTool {
        public String name() { return "glob"; }
        public String description() { return "Find files matching a glob pattern."; }
        public Map<String, Object> parameters() {
            return params(Map.of(
                    "pattern", prop("string", "Glob pattern, e.g. '**/*.py'"),
                    "path", prop("string", "Directory to search in (default: cwd)")
            ), "pattern");
        }

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
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static class GrepTool extends BaseTool {
        private static final Set<String> SKIP = Set.of(".git", "node_modules", "__pycache__", ".venv", "venv", ".tox", "dist", "build", "target");

        public String name() { return "grep"; }
        public String description() { return "Search file contents with regex."; }
        public Map<String, Object> parameters() {
            return params(Map.of(
                    "pattern", prop("string", "Regex pattern to search for"),
                    "path", prop("string", "File or directory to search (default: cwd)"),
                    "include", prop("string", "Only search files matching this glob")
            ), "pattern");
        }

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
                    try {
                        lines = Files.readString(fp).lines().toList();
                    } catch (Exception ignored) {
                        continue;
                    }
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

    private static class AgentTool extends BaseTool {
        private final Agent parent;

        AgentTool(Agent parent) {
            this.parent = parent;
        }

        public String name() { return "agent"; }
        public String description() { return "Spawn a sub-agent to handle a complex sub-task independently."; }
        public Map<String, Object> parameters() {
            return params(Map.of("task", prop("string", "What the sub-agent should accomplish")), "task");
        }

        public String execute(Map<String, Object> args) {
            if (parent == null) return "Error: agent tool not initialized (no parent agent)";
            try {
                List<Tool> subTools = parent.tools.stream().filter(t -> !"agent".equals(t.name())).toList();
                Agent sub = new Agent(parent.llm, subTools, parent.context.maxTokens, 20);
                String result = sub.chat(str(args, "task", ""), null, null);
                if (result.length() > 5000) result = result.substring(0, 4500) + "\n... (sub-agent output truncated)";
                return "[Sub-agent completed]\n" + result;
            } catch (Exception e) {
                return "Sub-agent error: " + e.getMessage();
            }
        }
    }
}
