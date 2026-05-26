package com.coder.skill;

import java.nio.file.Path;

public record Skill(String name, String description, String content, Path path) {
    public Skill(String name, String description, String content) {
        this(name, description, content, null);
    }

    public Path dir() {
        return path == null ? null : path.getParent();
    }
}
