package com.yang.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.yang.skill.Skill;
import com.yang.tool.Tools;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 加载和组装系统提示词、压缩提示词及动态 system-reminder。 */
public class Prompt {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM_PROMPT = load("system-prompt.md");
    private static final String COMPRESSION_PROMPT = load("conversation-summarization.md");
    private static final String PLANNER_PROMPT = load("planner-prompt.md");
    private static final String RUNTIME_REMINDER = load("runtime-reminder.md");
    private static final String MCP_TOOLS_REMINDER = load("mcp-tools-reminder.md");
    private static final String SKILLS_REMINDER = load("skills-reminder.md");

    public static String systemPrompt(List<Tools.Tool> tools) {
        return systemPrompt(tools, List.of());
    }

    public static String systemPrompt(List<Tools.Tool> tools, List<Skill> skills) {
        return SYSTEM_PROMPT
                .replace("{{cwd}}", Path.of("").toAbsolutePath().toString())
                .replace("{{tools_json}}", toolsJson(tools));
    }

    public static String systemReminder(List<Tools.Tool> mcpTools, List<Skill> skills) {
        StringBuilder s = new StringBuilder();
        s.append("<system-reminder>\n");
        s.append(RUNTIME_REMINDER.replace("{{current_datetime}}", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).strip());
        String mcp = mcpToolsSection(mcpTools);
        if (!mcp.isBlank()) s.append("\n\n").append(mcp);
        String skill = skillsSection(skills);
        if (!skill.isBlank()) s.append("\n\n").append(skill);
        s.append("\n\n这些信息来自系统动态注入，不是用户输入。仅在相关时使用。\n");
        s.append("</system-reminder>");
        return s.toString();
    }

    public static String plannerPrompt() {
        return PLANNER_PROMPT;
    }

    public static String compressionPrompt() {
        return COMPRESSION_PROMPT;
    }

    private static String mcpToolsSection(List<Tools.Tool> mcpTools) {
        if (!mcpTools.isEmpty()) {
            StringBuilder tools = new StringBuilder();
            for (Tools.Tool t : mcpTools) {
                tools.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
            }
            return MCP_TOOLS_REMINDER.replace("{{mcp_tools}}", tools.toString().stripTrailing()).strip();
        }
        return "";
    }

    private static String skillsSection(List<Skill> skills) {
        if (!skills.isEmpty()) {
            StringBuilder items = new StringBuilder();
            for (Skill skill : skills) {
                items.append("\n## ").append(skill.name()).append('\n');
                if (!skill.description().isBlank()) items.append(skill.description()).append('\n');
            }
            return SKILLS_REMINDER.replace("{{skills}}", items.toString().stripTrailing()).strip();
        }
        return "";
    }

    private static String toolsJson(List<Tools.Tool> tools) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tools.stream().map(Tools.Tool::schema).toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String load(String name) {
        try (var in = Prompt.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("缺少提示词资源: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词资源失败: " + name, e);
        }
    }
}
