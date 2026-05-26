package com.coder.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.coder.skill.Skill;

public final class ReadSkillTool extends ToolBase {
    private final List<Skill> skills;

    public ReadSkillTool(List<Skill> skills) {
        this.skills = List.copyOf(skills);
    }

    @Override
    public String name() {
        return "ReadSkill";
    }

    @Override
    public String description() {
        return "按名称读取已安装 Skill 的完整正文。需要使用某个 Skill 时，先调用此工具加载该 Skill 的指令。";
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "name", prop("string", "要读取的 Skill 名称")
        ), "name");
    }

    @Override
    public String execute(Map<String, Object> args) {
        String name = str(args, "name", "").trim();
        for (Skill skill : skills) {
            if (skill.name().equals(name)) return content(skill);
        }
        return "错误: 未找到 Skill " + name + "。可用 Skill: " + names();
    }

    private String content(Skill skill) {
        StringBuilder s = new StringBuilder();
        s.append("# ").append(skill.name()).append('\n');
        if (!skill.description().isBlank()) s.append(skill.description()).append('\n');
        if (skill.path() != null) s.append("SKILL.md: ").append(skill.path().toAbsolutePath()).append('\n');
        if (skill.dir() != null) s.append("目录: ").append(skill.dir().toAbsolutePath()).append('\n');
        s.append("\n# 使用说明\n");
        s.append("- 先遵循下面的 SKILL.md 正文。\n");
        s.append("- 需要参考资料时，用 Read 读取 references 下的具体文件。\n");
        s.append("- 需要执行脚本时，用 Bash 执行 scripts 下的脚本。\n");
        s.append("- assets、templates 等资源用于产物生成，按需读取或复制，不要一次性塞满上下文。\n\n");
        s.append(skill.content());
        String resources = resources(skill.dir());
        if (!resources.isBlank()) s.append("\n\n# 资源索引\n").append(resources);
        return s.toString().strip();
    }

    private String resources(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return "";
        try (var stream = Files.walk(dir, 4)) {
            return String.join("\n", stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.equals(dir.resolve("SKILL.md")))
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                    .limit(120)
                    .map(p -> "- [" + kind(dir.relativize(p)) + "] " + dir.relativize(p))
                    .toList());
        } catch (IOException e) {
            return "错误: 无法读取 Skill 资源索引: " + e.getMessage();
        }
    }

    private String kind(Path relative) {
        String first = relative.getNameCount() == 0 ? "" : relative.getName(0).toString();
        return switch (first) {
            case "scripts" -> "脚本";
            case "references" -> "参考";
            case "assets", "templates" -> "资源";
            case "agents" -> "元数据";
            default -> "文件";
        };
    }

    private String names() {
        return String.join(", ", skills.stream().map(Skill::name).toList());
    }
}
