package com.yang.skill;

import java.nio.file.Path;

/** 表示一个已加载 Skill 的元数据、正文和文件路径。 */
public record Skill(String name, String description, String content, Path path) {
    public Skill(String name, String description, String content) {
        this(name, description, content, null);
    }

    public Path dir() {
        return path == null ? null : path.getParent();
    }
}
