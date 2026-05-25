package com.coder.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SkillLoader {
    private SkillLoader() {
    }

    public static List<Skill> loadDefault() {
        return load(Path.of(System.getProperty("user.home"), ".mosaiccoder", "skills"));
    }

    public static List<Skill> load(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Skill> skills = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path child : stream.filter(Files::isDirectory).sorted().toList()) {
                Path file = child.resolve("SKILL.md");
                if (Files.isRegularFile(file)) skills.add(parse(child.getFileName().toString(), Files.readString(file)));
            }
        } catch (IOException ignored) {
        }
        return skills.stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    static Skill parse(String fallbackName, String text) {
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
        return new Skill(name, description, content);
    }

    private static String unquote(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
