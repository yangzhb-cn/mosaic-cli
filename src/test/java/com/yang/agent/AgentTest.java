package com.yang.agent;

import com.yang.audit.ToolAudit;
import com.yang.llm.LlmClient;
import com.yang.memory.MemoryManager;
import com.yang.plan.ExecutionPlan;
import com.yang.plan.PlanRunner;
import com.yang.plan.PlanTask;
import com.yang.plan.TaskType;
import com.yang.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.yang.skill.Skill;
import com.yang.tool.Tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest {
    @Test
    void startsStreamingToolsBeforeLlmResponseCompletesAndKeepsResultOrder() throws Exception {
        // 两个工具都启动后，假 LLM 才允许第一轮响应返回。
        CountDownLatch started = new CountDownLatch(2);
        // 假 LLM 会在第一轮流式阶段主动触发两个 tool-ready 回调。
        StreamingFakeLlm llm = new StreamingFakeLlm(started);
        // Slow 故意更慢，用来证明结果仍按 tool_calls 原始顺序写回。
        List<Tools.Tool> tools = List.of(new TestTool("Slow", "slow", 150, started), new TestTool("Fast", "fast", 0, started));
        // 使用包内构造函数注入假 LLM 和测试工具。
        Agent agent = new Agent(llm, tools, 128000, 3);

        // 触发一次完整的“模型 -> 工具 -> 模型”循环。
        String response = agent.chat("run tools", null, null);

        // 最终回答来自第二轮 LLM。
        assertEquals("done", response);
        // 如果工具没有在第一轮 LLM 返回前启动，这个断言会失败。
        assertTrue(llm.toolsStartedBeforeResponse);
        // 虽然 Fast 先完成，写回 messages 的结果仍按 Slow、Fast 顺序。
        assertEquals("slow", agent.messages.get(2).get("content"));
        assertEquals("fast", agent.messages.get(3).get("content"));
        // tool_call_id 也保持与原始 tool_calls 顺序一致。
        assertEquals("call_slow", agent.messages.get(2).get("tool_call_id"));
        assertEquals("call_fast", agent.messages.get(3).get("tool_call_id"));
        assertEquals(new Agent.TokenUsage(30, 10, 7, 20, 128000), agent.lastTokenUsage());
    }

    @Test
    void streamsTextTokensOnce() throws Exception {
        TextFakeLlm llm = new TextFakeLlm();
        Agent agent = new Agent(llm, List.of(), 128000, 1);
        List<String> tokens = new java.util.ArrayList<>();

        String response = agent.chat("say done", tokens::add, null);

        assertEquals("done", response);
        assertEquals(List.of("done"), tokens);
    }

    @Test
    void injectsSystemReminderIntoRequestOnly() throws Exception {
        CapturingFakeLlm llm = new CapturingFakeLlm();
        Agent agent = new Agent(llm, List.of(
                new SimpleTool("Normal", "Normal tool"),
                new SimpleTool("mcp_demo_echo", "MCP echo")
        ), 128000, 1, List.of(new Skill("test-skill", "Test skill", "Skill body")));

        String response = agent.chat("hello", null, null);

        assertEquals("done", response);
        String system = String.valueOf(llm.lastMessages.getFirst().get("content"));
        String sent = String.valueOf(llm.lastMessages.getLast().get("content"));
        assertTrue(system.contains("\"name\" : \"Normal\""));
        assertTrue(system.contains("\"description\" : \"Normal tool\""));
        assertFalse(system.contains("mcp_demo_echo"));
        assertTrue(sent.startsWith("<system-reminder>"));
        assertFalse(sent.contains("Normal tool"));
        assertTrue(sent.contains("- mcp_demo_echo: MCP echo"));
        assertTrue(sent.contains("## test-skill"));
        assertTrue(sent.endsWith("hello"));
        assertEquals("hello", agent.messages.getFirst().get("content"));
        assertFalse(String.valueOf(agent.messages.getFirst().get("content")).contains("<system-reminder>"));
    }

    @Test
    void injectsWorkspaceMemoryIntoSystemReminderDynamically(@TempDir Path temp) throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));
        memory.ensureWorkspace();
        Files.writeString(memory.memoryFile(), "first memory");
        CapturingFakeLlm llm = new CapturingFakeLlm();
        Agent agent = new Agent(llm, List.of(), 128000, 1, List.of(), new ToolAudit(), memory);

        agent.chat("hello", null, null);

        String first = String.valueOf(llm.lastMessages.getLast().get("content"));
        assertTrue(first.contains("<system-reminder>"));
        assertTrue(first.contains("# Long-term memory"));
        assertTrue(first.contains("first memory"));
        assertEquals("hello", agent.messages.getFirst().get("content"));
        assertFalse(String.valueOf(agent.messages.getFirst().get("content")).contains("first memory"));

        Files.writeString(memory.memoryFile(), "second memory");
        agent.chat("again", null, null);

        String second = String.valueOf(llm.lastMessages.getLast().get("content"));
        assertTrue(second.contains("second memory"));
        assertFalse(second.contains("first memory"));
    }

    @Test
    void subAgentAlsoReceivesWorkspaceMemory(@TempDir Path temp) throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));
        memory.ensureWorkspace();
        Files.writeString(memory.memoryFile(), "subagent memory");
        CapturingFakeLlm llm = new CapturingFakeLlm();
        Agent agent = new Agent(llm, List.of(), 128000, 1, List.of(), new ToolAudit(), memory);

        String response = agent.runSubAgent("do sub task", 1);

        assertEquals("done", response);
        String sent = String.valueOf(llm.lastMessages.getLast().get("content"));
        assertTrue(sent.contains("# Long-term memory"));
        assertTrue(sent.contains("subagent memory"));
    }

    @Test
    void subAgentDoesNotArchiveInternalConversation(@TempDir Path temp) throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));
        memory.ensureWorkspace();
        Agent agent = new Agent(new CapturingFakeLlm(), List.of(), 128000, 1, List.of(), new ToolAudit(), memory);

        agent.runSubAgent("internal task", 1);

        Path archive = memory.conversationsDir().resolve(java.time.LocalDate.now() + ".md");
        assertFalse(Files.exists(archive));
    }

    @Test
    void planRunnerReportsSubAgentInternalToolCalls() throws Exception {
        Agent agent = new Agent(new SubAgentToolCallingLlm(), List.of(new StrictTool()), 128000, 3);
        ExecutionPlan plan = new ExecutionPlan("task", List.of(
                new PlanTask("T1", "inspect", TaskType.FILE_READ, List.of())
        ));
        List<String> progress = new java.util.ArrayList<>();

        new PlanRunner(agent).run(plan, progress::add);

        assertTrue(progress.stream().anyMatch(s -> s.contains("SubAgent(T1).Strict") && s.contains("input=ok")));
    }

    @Test
    void chatAutoSavesActiveSessionWithoutMarkdownArchive(@TempDir Path temp) throws Exception {
        MemoryManager memory = MemoryManager.forWorkspace(temp.resolve("workspace"));
        memory.ensureWorkspace();
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        SessionManager.Session active = sessions.loadActiveOrCreate("test-model");
        CapturingFakeLlm llm = new CapturingFakeLlm();
        Agent agent = new Agent(llm, List.of(), 128000, 1, List.of(), new ToolAudit(temp.resolve("audits")), memory, sessions);
        agent.loadSession(active.messages(), active.conversationId());

        agent.chat("remember this", null, null);

        SessionManager.Session saved = sessions.load(active.id());
        assertNotNull(saved);
        assertEquals("remember this", saved.messages().getFirst().get("content"));
        assertEquals(agent.conversationId(), saved.conversationId());
        Path archive = memory.conversationsDir().resolve(java.time.LocalDate.now() + ".md");
        assertFalse(Files.exists(archive));
        String jsonl = Files.readString(jsonlFiles(temp.resolve("data")).getFirst());
        assertTrue(jsonl.contains("\"type\":\"response_item\""));
        assertTrue(jsonl.contains("remember this"));
        assertTrue(jsonl.contains("done"));
    }

    @Test
    void toolResultsArePersistedAndRestoredFromSessionJsonl(@TempDir Path temp) throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        StreamingFakeLlm llm = new StreamingFakeLlm(started);
        List<Tools.Tool> tools = List.of(new TestTool("Slow", "slow", 0, started), new TestTool("Fast", "fast", 0, started));
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        SessionManager.Session active = sessions.loadActiveOrCreate("test-model");
        Agent agent = new Agent(llm, tools, 128000, 3, List.of(), new ToolAudit(temp.resolve("audits")), MemoryManager.disabled(), sessions);
        agent.loadSession(active.messages(), active.conversationId(), active.auditRecords());

        agent.chat("run tools", null, null);

        SessionManager.Session saved = sessions.load(active.id());
        assertEquals("tool", saved.messages().get(2).get("role"));
        assertEquals("slow", saved.messages().get(2).get("content"));
        String jsonl = Files.readString(jsonlFiles(temp.resolve("data")).getFirst());
        assertTrue(jsonl.contains("\"type\":\"response_item\""));
        assertTrue(jsonl.contains("\"type\":\"event_msg\""));
        assertTrue(jsonl.contains("call_slow"));
        assertTrue(jsonl.contains("slow"));
    }

    @Test
    void autoSavedSessionRestoresAuditRecords(@TempDir Path temp) throws Exception {
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        SessionManager.Session active = sessions.loadActiveOrCreate("test-model");
        Agent agent = new Agent(new CapturingFakeLlm(), List.of(), 128000, 1, List.of(), new ToolAudit(temp.resolve("audits")), MemoryManager.disabled(), sessions);
        agent.loadSession(active.messages(), active.conversationId(), active.auditRecords());
        agent.audit().record("Read", true, 1_000_000);

        agent.saveSession();
        SessionManager.Session saved = sessions.load(active.id());
        Agent restored = new Agent(new CapturingFakeLlm(), List.of(), 128000, 1, List.of(), new ToolAudit(temp.resolve("audits")), MemoryManager.disabled(), sessions);
        restored.loadSession(saved.messages(), saved.conversationId(), saved.auditRecords());

        assertEquals(agent.conversationId(), restored.conversationId());
        assertTrue(restored.audit().table().contains("Read"));
        assertTrue(restored.audit().table().contains("100.00%"));
    }

    @Test
    void cliPlanningUsesPlannerAndKeepsPlannerMessagesOutOfMainSession() throws Exception {
        PlanningFakeLlm llm = new PlanningFakeLlm();
        Agent agent = new Agent(llm, List.of(), 128000, 1);
        agent.enterPlanMode();

        String response = agent.chatCli("plan this", null, null);

        assertTrue(response.contains("ID"));
        assertTrue(response.contains("T1"));
        assertTrue(response.contains("/act 执行"));
        assertEquals("plan this", agent.messages.getFirst().get("content"));
        assertEquals(2, agent.messages.size());
        assertTrue(String.valueOf(llm.lastMessages.getFirst().get("content")).contains("Planner Agent"));
        assertFalse(String.valueOf(agent.messages.getLast().get("content")).contains("Planner Agent"));
        assertTrue(agent.planSession().ready());
    }

    @Test
    void cliPlanningAutoSavesActiveSession(@TempDir Path temp) throws Exception {
        PlanningFakeLlm llm = new PlanningFakeLlm();
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        SessionManager.Session active = sessions.loadActiveOrCreate("test-model");
        Agent agent = new Agent(llm, List.of(), 128000, 1, List.of(), new ToolAudit(temp.resolve("audits")), MemoryManager.disabled(), sessions);
        agent.loadSession(active.messages(), active.conversationId());
        agent.enterPlanMode();

        agent.chatCli("plan this", null, null);

        SessionManager.Session saved = sessions.load(active.id());
        assertNotNull(saved);
        assertEquals(2, saved.messages().size());
        assertEquals("plan this", saved.messages().getFirst().get("content"));
    }

    @Test
    void imChatDoesNotConsumeCliPlanInput() throws Exception {
        CapturingFakeLlm llm = new CapturingFakeLlm();
        Agent agent = new Agent(llm, List.of(), 128000, 1);
        agent.enterPlanMode();

        String response = agent.chatFromIm("chat-1", "hello from im");

        assertEquals("done", response);
        assertTrue(agent.planSession().awaitingTask());
        assertEquals("hello from im", agent.messages.getFirst().get("content"));
    }

    @Test
    void rejectsUnknownToolArgumentsBeforeExecute() throws Exception {
        InvalidArgsFakeLlm llm = new InvalidArgsFakeLlm();
        StrictTool tool = new StrictTool();
        Agent agent = new Agent(llm, List.of(tool), 128000, 3);

        String response = agent.chat("run strict tool", null, null);

        assertEquals("fixed", response);
        assertEquals(0, tool.executions);
        assertTrue(String.valueOf(agent.messages.get(2).get("content")).contains("未知参数 Strict.extra"));
    }

    @Test
    void resetClearsAuditAndConversation() {
        Agent agent = new Agent(new TextFakeLlm(), List.of(), 128000, 1);
        String before = agent.conversationId();
        agent.audit().record("Read", true, 1_000_000);

        agent.reset();

        assertTrue(agent.audit().isEmpty());
        assertNotEquals(before, agent.conversationId());
    }

    private static final class TextFakeLlm extends LlmClient {
        private TextFakeLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            if (onToken != null) onToken.accept("done");
            return new Response("done", "", List.of(), 0, 0, 0);
        }
    }

    private static List<Path> jsonlFiles(Path data) throws Exception {
        try (var stream = Files.walk(data.resolve("sessions"))) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
        }
    }

    private static final class CapturingFakeLlm extends LlmClient {
        private List<Map<String, Object>> lastMessages;

        private CapturingFakeLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            lastMessages = messages;
            return new Response("done", "", List.of(), 0, 0, 0);
        }
    }

    private static final class PlanningFakeLlm extends LlmClient {
        private List<Map<String, Object>> lastMessages;

        private PlanningFakeLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            lastMessages = messages;
            return new Response("{\"tasks\":[{\"id\":\"T1\",\"description\":\"read files\",\"type\":\"FILE_READ\",\"dependencies\":[]}]}", "", List.of(), 0, 0, 0);
        }
    }

    private static final class StreamingFakeLlm extends LlmClient {
        private final CountDownLatch started;
        private boolean toolsStartedBeforeResponse;
        private int calls;

        private StreamingFakeLlm(CountDownLatch started) {
            super("test-model", "key", "http://localhost", 0);
            this.started = started;
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) throws IOException {
            // 第一轮返回工具调用，第二轮返回最终文本。
            calls++;
            if (calls == 1) {
                // 构造两个按顺序排列的工具调用。
                ToolCall slow = new ToolCall("call_slow", "Slow", Map.of());
                ToolCall fast = new ToolCall("call_fast", "Fast", Map.of());
                // 模拟 SSE 中第一个工具参数已经完整，立即通知 Agent。
                onToolReady.accept(0, slow);
                // 模拟 SSE 中第二个工具参数也已经完整，立即通知 Agent。
                onToolReady.accept(1, fast);
                try {
                    // 在 LLM 第一轮返回前等待工具真正开始执行。
                    toolsStartedBeforeResponse = started.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 第一轮响应仍然返回完整 tool_calls，供 Agent 写入 assistant 消息。
                return new Response("", "", List.of(slow, fast), 10, 3, 2);
            }
            // 第二轮模拟普通文本流式输出。
            if (onToken != null) onToken.accept("done");
            // 第二轮没有工具调用，结束 Agent.chat。
            return new Response("done", "", List.of(), 20, 7, 5);
        }
    }

    private static final class InvalidArgsFakeLlm extends LlmClient {
        private int calls;

        private InvalidArgsFakeLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            calls++;
            if (calls == 1) {
                ToolCall call = new ToolCall("call_strict", "Strict", Map.of("input", "ok", "extra", "ignored"));
                if (onToolReady != null) onToolReady.accept(0, call);
                return new Response("", "", List.of(call), 0, 0, 0);
            }
            return new Response("fixed", "", List.of(), 0, 0, 0);
        }
    }

    private static final class SubAgentToolCallingLlm extends LlmClient {
        private int calls;

        private SubAgentToolCallingLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            calls++;
            if (calls == 1) {
                ToolCall call = new ToolCall("call_strict", "Strict", Map.of("input", "ok"));
                if (onToolReady != null) onToolReady.accept(0, call);
                return new Response("", "", List.of(call), 0, 0, 0);
            }
            return new Response("done", "", List.of(), 0, 0, 0);
        }
    }

    private static final class TestTool implements Tools.Tool {
        private final String name;
        private final String result;
        private final long delay;
        private final CountDownLatch started;

        private TestTool(String name, String result, long delay, CountDownLatch started) {
            this.name = name;
            this.result = result;
            this.delay = delay;
            this.started = started;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        @Override
        public String execute(Map<String, Object> args) {
            // 标记该工具已经被线程池启动。
            started.countDown();
            try {
                // Slow 工具通过 delay 制造“后完成”的情况。
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 返回可断言的工具结果。
            return result;
        }
    }

    private static final class SimpleTool implements Tools.Tool {
        private final String name;
        private final String description;

        private SimpleTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "";
        }
    }

    private static final class StrictTool implements Tools.Tool {
        private int executions;

        @Override
        public String name() {
            return "Strict";
        }

        @Override
        public String description() {
            return "Strict test tool";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "properties", Map.of("input", Map.of("type", "string")),
                    "required", List.of("input")
            );
        }

        @Override
        public String execute(Map<String, Object> args) {
            executions++;
            return "executed";
        }
    }
}
