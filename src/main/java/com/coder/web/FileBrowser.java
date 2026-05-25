package com.coder.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class FileBrowser {
    private final List<Path> roots;

    FileBrowser(Path launchCwd) {
        this(List.of(Path.of(System.getProperty("user.home")), launchCwd));
    }

    FileBrowser(List<Path> roots) {
        this.roots = roots.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    Map<String, Object> list(String rawPath) throws IOException {
        Path dir = allowed(rawPath);
        if (!Files.isDirectory(dir)) throw new IOException("not a directory");
        try (var stream = Files.list(dir)) {
            List<Map<String, Object>> entries = stream
                    .filter(this::visible)
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .map(p -> Map.<String, Object>of(
                            "name", p.getFileName().toString(),
                            "path", p.toAbsolutePath().normalize().toString(),
                            "isDir", Files.isDirectory(p),
                            "size", size(p)
                    ))
                    .toList();
            return Map.of("path", dir.toString(), "entries", entries);
        }
    }

    Map<String, Object> read(String rawPath) throws IOException {
        Path file = allowed(rawPath);
        if (!Files.isRegularFile(file)) throw new IOException("not a file");
        return Map.of(
                "path", file.toString(),
                "name", file.getFileName().toString(),
                "content", Files.readString(file)
        );
    }

    boolean isAllowed(String rawPath) {
        try {
            allowed(rawPath);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Path allowed(String rawPath) throws IOException {
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        for (Path root : roots) {
            if (path.startsWith(root)) return path;
        }
        throw new IOException("path is outside the allowed roots");
    }

    private boolean visible(Path path) {
        String name = path.getFileName().toString();
        return !name.equals(".git") && !name.equals("target") && !name.equals("node_modules") && !name.equals(".next");
    }

    private long size(Path path) {
        try {
            return Files.isDirectory(path) ? 0L : Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }
}
