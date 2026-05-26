package com.coder.skill;

import com.coder.prompt.Prompt;
import com.coder.tool.Tools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {
    @TempDir
    Path temp;

    @Test
    void loadsSkillMarkdownFiles() throws Exception {
        Path dir = temp.resolve("skills").resolve("web-access");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: web-access
                description: Use browser access
                ---
                Use web tools for current external information.
                """);

        List<Skill> skills = SkillLoader.load(temp.resolve("skills"));

        assertEquals(1, skills.size());
        assertEquals("web-access", skills.getFirst().name());
        assertEquals("Use browser access", skills.getFirst().description());
        assertEquals("Use web tools for current external information.", skills.getFirst().content());
        assertEquals(dir.resolve("SKILL.md"), skills.getFirst().path());
    }

    @Test
    void missingSkillDirectoryLoadsEmpty() {
        assertTrue(SkillLoader.load(temp.resolve("missing")).isEmpty());
    }

    @Test
    void toolsStayInSystemPromptAndMcpSkillsMoveToReminder() {
        Tools.Tool tool = new Tools.Tool() {
            @Override
            public String name() {
                return "SearchTool";
            }

            @Override
            public String description() {
                return "Search test files";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of();
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "";
            }
        };
        Tools.Tool mcp = new Tools.Tool() {
            @Override
            public String name() {
                return "mcp_demo_search";
            }

            @Override
            public String description() {
                return "Search from MCP";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of();
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "";
            }
        };
        Path skillPath = temp.resolve("skills").resolve("web-access").resolve("SKILL.md");
        Skill skill = new Skill("web-access", "Use web", "Use browser for tests only.", skillPath);

        String prompt = Prompt.systemPrompt(List.of(tool), List.of(skill));
        String reminder = Prompt.systemReminder(List.of(mcp), List.of(skill));

        assertFalse(prompt.contains("# Skills"));
        assertFalse(prompt.contains("Use browser for tests only."));
        assertFalse(prompt.contains("安装 MCP 时"));
        assertTrue(prompt.contains("工作目录:"));
        assertTrue(prompt.contains("\"name\" : \"SearchTool\""));
        assertTrue(prompt.contains("\"description\" : \"Search test files\""));
        assertTrue(reminder.startsWith("<system-reminder>"));
        assertTrue(reminder.contains("# 工具选择优先级"));
        assertTrue(reminder.contains("安装 MCP 时"));
        assertFalse(reminder.contains("SearchTool: Search test files"));
        assertTrue(reminder.contains("- mcp_demo_search: Search from MCP"));
        assertTrue(reminder.contains("## web-access"));
        assertTrue(reminder.contains("Use web"));
        assertTrue(reminder.contains("ReadSkill(name=...)"));
        assertTrue(reminder.contains("scripts、references、assets、templates"));
        assertFalse(reminder.contains(skillPath.toString()));
        assertFalse(reminder.contains("Use browser for tests only."));
        assertTrue(reminder.endsWith("</system-reminder>"));
    }

    @Test
    void systemReminderStillInjectsToolPolicyWithoutDynamicExtensions() {
        String reminder = Prompt.systemReminder(List.of(), List.of());

        assertTrue(reminder.startsWith("<system-reminder>"));
        assertTrue(reminder.contains("# 工具选择优先级"));
        assertFalse(reminder.contains("# MCP 工具"));
        assertFalse(reminder.contains("# Skills"));
    }
}
