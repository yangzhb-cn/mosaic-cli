package com.coder;

import org.junit.jupiter.api.Test;

import com.coder.skill.Skill;
import com.coder.tool.Tools;

import java.io.IOException;
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
    void rejectsUnknownToolArgumentsBeforeExecute() throws Exception {
        InvalidArgsFakeLlm llm = new InvalidArgsFakeLlm();
        StrictTool tool = new StrictTool();
        Agent agent = new Agent(llm, List.of(tool), 128000, 3);

        String response = agent.chat("run strict tool", null, null);

        assertEquals("fixed", response);
        assertEquals(0, tool.executions);
        assertTrue(String.valueOf(agent.messages.get(2).get("content")).contains("未知参数 Strict.extra"));
    }

    private static final class TextFakeLlm extends LlmClient {
        private TextFakeLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            if (onToken != null) onToken.accept("done");
            return new Response("done", "", List.of(), 0, 0);
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
            return new Response("done", "", List.of(), 0, 0);
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
                return new Response("", "", List.of(slow, fast), 0, 0);
            }
            // 第二轮模拟普通文本流式输出。
            if (onToken != null) onToken.accept("done");
            // 第二轮没有工具调用，结束 Agent.chat。
            return new Response("done", "", List.of(), 0, 0);
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
                return new Response("", "", List.of(call), 0, 0);
            }
            return new Response("fixed", "", List.of(), 0, 0);
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
