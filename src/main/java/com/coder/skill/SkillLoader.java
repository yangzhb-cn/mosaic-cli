package com.coder.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillLoader {
    private SkillLoader() {
    }

    public static List<Skill> loadDefault() {
        return load(List.of(
                Path.of(System.getProperty("user.home"), ".mosaiccoder", "skills"),
                Path.of("").toAbsolutePath().resolve(".mosaiccoder").resolve("skills")
        ));
    }

    public static List<Skill> load(List<Path> dirs) {
        Map<String, Skill> skills = new LinkedHashMap<>();
        for (Path dir : dirs) {
            for (Skill skill : load(dir)) skills.put(skill.name(), skill);
        }
        return skills.values().stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    public static List<Skill> load(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Skill> skills = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path child : stream.filter(Files::isDirectory).sorted().toList()) {
                Path file = child.resolve("SKILL.md");
                if (Files.isRegularFile(file)) skills.add(parse(child.getFileName().toString(), Files.readString(file), file));
            }
        } catch (IOException ignored) {
        }
        return skills.stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    static Skill parse(String fallbackName, String text) {
        return parse(fallbackName, text, null);
    }

    static Skill parse(String fallbackName, String text, Path path) {
        String name = fallbackName;
        String description = "";
        String content = text.strip();
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                String meta = text.substring(3, end).strip();
                content = text.substring(end + 4).strip();
                for (String line : meta.split("\\R")) {
                    int i = line.indexOf(':');
                    if (i < 0) continue;
                    String key = line.substring(0, i).trim();
                    String value = unquote(line.substring(i + 1).trim());
                    if ("name".equals(key) && !value.isBlank()) name = value;
                    if ("description".equals(key)) description = value;
                }
            }
        }
        return new Skill(name, description, content, path);
    }

    private static String unquote(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
