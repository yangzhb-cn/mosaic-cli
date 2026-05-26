package com.coder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.coder.im.ImClient;
import com.coder.im.ImMessage;
import com.coder.skill.Skill;
import com.coder.tool.Tools;
import com.coder.tool.WebSearchTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ToolsTest {
    @TempDir
    Path temp;

    @Test
    void allToolsExposeSchemas() {
        List<Tools.Tool> tools = Tools.all(null);
        assertEquals(13, tools.size());
        assertNotNull(Tools.get(tools, "WebFetch"));
        assertNotNull(Tools.get(tools, "WebSearch"));
        for (Tools.Tool t : tools) {
            Map<String, Object> schema = t.schema();
            assertEquals("function", schema.get("type"));
            Map<?, ?> fn = (Map<?, ?>) schema.get("function");
            assertTrue(fn.containsKey("parameters"));
            assertEquals(false, ((Map<?, ?>) fn.get("parameters")).get("additionalProperties"));
        }
    }

    @Test
    void allToolsCanAppendExtraTools() {
        Tools.Tool extra = new ExtraTool();
        List<Tools.Tool> tools = Tools.all(null, List.of(extra));

        assertNotNull(Tools.get(tools, "mcp_demo_echo"));
        assertEquals(14, tools.size());
    }

    @Test
    void readSkillToolLoadsSkillContentAndResourceIndexOnDemand() throws Exception {
        Path dir = temp.resolve("skills/web-access");
        Files.createDirectories(dir.resolve("scripts"));
        Files.createDirectories(dir.resolve("references"));
        Files.createDirectories(dir.resolve("assets"));
        Files.writeString(dir.resolve("scripts/fetch.py"), "print('ok')");
        Files.writeString(dir.resolve("references/api.md"), "# API");
        Files.writeString(dir.resolve("assets/template.txt"), "template");
        Skill skill = new Skill("web-access", "Use web", "Skill body", dir.resolve("SKILL.md"));
        List<Tools.Tool> tools = Tools.all(null, List.of(), List.of(skill));
        Tools.Tool readSkill = Tools.get(tools, "ReadSkill");
        String content = readSkill.execute(Map.of("name", "web-access"));

        assertNotNull(readSkill);
        assertTrue(content.contains("Skill body"));
        assertTrue(content.contains("[脚本] scripts/fetch.py"));
        assertTrue(content.contains("[参考] references/api.md"));
        assertTrue(content.contains("[资源] assets/template.txt"));
        assertTrue(readSkill.execute(Map.of("name", "missing")).contains("可用 Skill: web-access"));
    }

    @Test
    void bashRunsCommandsAndBlocksDangerousCommands() {
        Tools.Tool bash = Tools.get(Tools.all(null), "Bash");
        assertNotNull(bash);
        assertTrue(bash.execute(Map.of("command", "echo hello")).contains("hello"));
        assertTrue(bash.execute(Map.of("command", "rm -rf /")).contains("已阻止"));
    }

    @Test
    void readWriteAndEditFiles() throws Exception {
        List<Tools.Tool> tools = Tools.all(null);
        Path file = temp.resolve("sample.txt");

        assertTrue(Tools.get(tools, "Write").execute(Map.of(
                "file_path", file.toString(),
                "content", "alpha\nbeta\n"
        )).contains("已写入"));

        assertTrue(Tools.get(tools, "Read").execute(Map.of("file_path", file.toString())).contains("1\talpha"));

        String edited = Tools.get(tools, "Edit").execute(Map.of(
                "file_path", file.toString(),
                "old_string", "beta",
                "new_string", "gamma"
        ));
        assertTrue(edited.contains("已编辑"));
        assertTrue(edited.contains("---"));
        assertEquals("alpha\ngamma\n", Files.readString(file));
        assertTrue(Tools.changedFiles().contains(file.toAbsolutePath().normalize().toString()));
    }

    @Test
    void editRequiresUniqueMatch() throws Exception {
        Path file = temp.resolve("dup.txt");
        Files.writeString(file, "x\nx\n");
        String out = Tools.get(Tools.all(null), "Edit").execute(Map.of(
                "file_path", file.toString(),
                "old_string", "x",
                "new_string", "y"
        ));
        assertTrue(out.contains("期望替换 1 处，实际找到 2 处"));
    }

    @Test
    void globGrepAndLsFindFiles() throws Exception {
        Path file = temp.resolve("note.txt");
        Files.writeString(file, "hello needle\n");
        assertTrue(Tools.get(Tools.all(null), "Glob").execute(Map.of(
                "pattern", "*.txt",
                "path", temp.toString()
        )).contains("note.txt"));
        assertTrue(Tools.get(Tools.all(null), "Grep").execute(Map.of(
                "pattern", "needle",
                "path", temp.toString()
        )).contains("note.txt"));
        assertTrue(Tools.get(Tools.all(null), "LS").execute(Map.of(
                "path", temp.toString()
        )).contains("note.txt"));
        assertTrue(Tools.get(Tools.all(null), "LS").execute(Map.of(
                "path", "."
        )).contains("绝对路径"));
    }

    @Test
    void multiEditAppliesEditsAtomically() throws Exception {
        Path file = temp.resolve("multi.txt");
        Files.writeString(file, "alpha\nbeta\nbeta\n");

        String failed = Tools.get(Tools.all(null), "MultiEdit").execute(Map.of(
                "file_path", file.toString(),
                "edits", List.of(Map.of(
                        "old_string", "beta",
                        "new_string", "gamma",
                        "expected_replacements", 1
                ))
        ));
        assertTrue(failed.contains("期望替换 1 处，实际找到 2 处"));
        assertEquals("alpha\nbeta\nbeta\n", Files.readString(file));

        String edited = Tools.get(Tools.all(null), "MultiEdit").execute(Map.of(
                "file_path", file.toString(),
                "edits", List.of(
                        Map.of("old_string", "alpha", "new_string", "one"),
                        Map.of("old_string", "beta", "new_string", "two", "expected_replacements", 2)
                )
        ));
        assertTrue(edited.contains("应用 2 处编辑"));
        assertEquals("one\ntwo\ntwo\n", Files.readString(file));
    }

    @Test
    void todoToolsStoreSessionTodos() {
        List<Map<String, Object>> todos = List.of(Map.of(
                "id", "1",
                "content", "test todo",
                "status", "in_progress",
                "priority", "high"
        ));
        assertTrue(Tools.get(Tools.all(null), "TodoWrite").execute(Map.of("todos", todos)).contains("1"));
        assertTrue(Tools.get(Tools.all(null), "TodoRead").execute(Map.of()).contains("test todo"));
    }

    @Test
    void agentToolNeedsParentAgent() {
        assertTrue(Tools.get(Tools.all(null), "Task").execute(Map.of("task", "x")).contains("未初始化"));
    }

    @Test
    void webSearchRequiresTavilyApiKey() {
        Tools.Tool search = new WebSearchTool(Map.of(), temp);
        assertTrue(search.execute(Map.of("query", "java")).contains("TAVILY_API_KEY"));
    }

    @Test
    void imAgentRegistersSendMessageTool() {
        FakeImClient im = new FakeImClient();
        Agent agent = new Agent(new LlmClient("m", "k", "http://localhost", 0), 1000, im);
        assertNotNull(Tools.get(agent.tools, "send_message"));
        assertEquals(14, agent.tools.size());
    }

    @Test
    void sendMessageToolUsesCurrentImChat() {
        FakeImClient im = new FakeImClient();
        Agent agent = new Agent(new LlmClient("m", "k", "http://localhost", 0), 1000, im);
        Tools.Tool send = Tools.get(agent.tools, "send_message");
        assertNotNull(send);

        assertTrue(send.execute(Map.of("text", "hi")).contains("当前没有可用的 IM 会话"));

        agent.setCurrentImChatId("chat-1");
        assertTrue(send.execute(Map.of("text", "hi")).contains("消息已发送"));
        assertEquals("chat-1", im.chatId);
        assertEquals("hi", im.text);
    }

    static class FakeImClient implements ImClient {
        String chatId;
        String text;

        @Override
        public void start(Consumer<ImMessage> handler) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void send(String chatId, String text) {
            this.chatId = chatId;
            this.text = text;
        }

        @Override
        public void typing(String chatId) {
        }
    }

    private static final class ExtraTool implements Tools.Tool {
        @Override
        public String name() {
            return "mcp_demo_echo";
        }

        @Override
        public String description() {
            return "extra tool";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object", "additionalProperties", false, "properties", Collections.emptyMap(), "required", List.of());
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "ok";
        }
    }
}
