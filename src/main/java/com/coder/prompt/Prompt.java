package com.coder.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.coder.skill.Skill;
import com.coder.tool.Tools;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Prompt {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM_PROMPT = load("system-prompt.md");
    private static final String COMPRESSION_PROMPT = load("conversation-summarization.md");

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
        s.append("""
                # 工具选择优先级
                - 代码库相关问题（类、函数、调用关系、哪里实现了某功能）优先用 Glob、Grep、Read、LS 或 Task，不要先用 WebSearch。
                - 语法、稳定 API、基础概念等训练数据中稳定的知识，可以直接回答，不要为了回答而联网。
                - 时效性、最新信息、不确定事实、外部文档或 API 参考，先用 WebSearch 找入口，找到 URL 后再用 WebFetch 获取全文。
                - 用户已经给出具体 URL 时，直接 WebFetch，不要再 WebSearch 一次。
                - WebFetch 返回空正文、疑似 SPA、防爬、需要登录态或需要交互时，优先使用可用的浏览器 MCP 工具，不要重复 WebFetch。

                # 网页内容获取
                - 静态/SSR 页面（博客、官方文档、wiki、GitHub README）优先 WebFetch。
                - SPA、React/Vue 客户端渲染页面、需要 JS 才有内容的页面，优先浏览器 MCP。
                - 防爬墙、登录态、点击/输入/提交等交互场景，优先浏览器 MCP。
                - 微信公众号、知乎专栏、推特、小红书等站点，WebFetch 通常不可靠，优先浏览器 MCP。
                - 已知 URL 先 WebFetch 试一次，失败再换浏览器 MCP。

                # 扩展能力安装
                - 如果当前工具或工作指南不足以完成用户需求，并且用户提供了 MCP 或 Skill 的安装信息，可以自己安装。
                - 安装 MCP 时，创建或更新 ~/.mosaiccoder/mcp.json，保留已有 server，不要覆盖无关配置。
                - MCP 配置结构必须是 {"mcpServers":{"name":{...}}}。
                - stdio MCP 使用 {"type":"stdio","command":"npx","args":["-y","package-name"],"env":{}}；command 只放可执行文件名，参数逐项放进 args。
                - HTTP MCP 使用 {"type":"http","url":"http://host:port","endpoint":"/mcp","headers":{}}；SSE MCP 使用 {"type":"sse","url":"http://host:port","endpoint":"/sse","headers":{}}。
                - 安装 Skill 时，创建或更新 ~/.mosaiccoder/skills/<name>/SKILL.md；Skill 可以包含 SKILL.md、scripts、references、assets、templates 等完整目录。
                - 安装 Skill 可以寻找本机或 GitHub。用户给出明确来源时按来源处理；没有明确来源时，先查本机常见目录，再查 GitHub。
                - 查本机时，优先检查 ~/.mosaiccoder/skills/<name>/SKILL.md、~/.codex/skills/.system/<name>/SKILL.md、~/.codex/skills/<name>/SKILL.md、~/.agents/skills/<name>/SKILL.md。
                - 查 GitHub 时，使用 WebSearch 或 WebFetch 寻找包含目标 SKILL.md 的仓库或目录；只有确认名称、描述和内容匹配用户要的 skill 后才安装。
                - 找到来源后复制完整 skill 目录到 ~/.mosaiccoder/skills/<name>，并提醒重启 CLI；不要把搜索结果中的无关同名仓库当作正确来源。

                """);
        if (!mcpTools.isEmpty()) {
            s.append("# MCP 工具\n");
            s.append("以下 MCP 工具来自本机 ~/.mosaiccoder/mcp.json，是动态加载的额外工具。\n");
            for (Tools.Tool t : mcpTools) {
                s.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
            }
        }
        if (!skills.isEmpty()) {
            s.append("\n# Skills\n");
            s.append("以下技能来自本机或当前项目的 .mosaiccoder/skills。这里只提供元数据；需要使用某个技能时，先调用 ReadSkill(name=...) 读取正文和资源索引，再按正文执行。\n");
            s.append("Skill 不只是提示词，还可能包含 scripts、references、assets、templates；只读取或执行当前任务需要的资源。\n");
            for (Skill skill : skills) {
                s.append("\n## ").append(skill.name()).append('\n');
                if (!skill.description().isBlank()) s.append(skill.description()).append('\n');
            }
        }
        s.append("\n这些信息来自系统动态注入，不是用户输入。仅在相关时使用。\n");
        s.append("</system-reminder>");
        return s.toString();
    }

    public static String compressionPrompt() {
        return COMPRESSION_PROMPT;
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
