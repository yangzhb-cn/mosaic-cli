package com.corecoder.tools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class BashTool extends ToolBase {
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

    @Override
    public String name() { return "Bash"; }

    @Override
    public String description() { return "执行 shell 命令，返回 stdout、stderr 和退出码。"; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "command", prop("string", "要运行的 shell 命令"),
                "timeout", prop("integer", "超时时间，单位秒，默认 120")
        ), "command");
    }

    @Override
    public String execute(Map<String, Object> args) {
        String command = str(args, "command", "");
        String blocked = dangerous(command);
        if (blocked != null) return "Blocked: " + blocked + "\nCommand: " + command;
        int timeout = integer(args, "timeout", 120);
        Path runDir = cwd == null ? Path.of("").toAbsolutePath() : cwd;
        List<String> shell = System.getProperty("os.name").toLowerCase().contains("win") ? List.of("cmd", "/c", command) : List.of("/bin/sh", "-c", command);
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
            try { return new String(in.readAllBytes(), StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
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
            Path next = raw.startsWith("~") ? Path.of(System.getProperty("user.home") + raw.substring(1)).toAbsolutePath().normalize() : cur.resolve(raw).normalize();
            if (Files.isDirectory(next)) cur = next;
        }
        cwd = cur;
    }
}
