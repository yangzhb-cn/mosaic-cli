package com.yang.plan;

import com.yang.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PlannerAgentTest {
    @Test
    void usesIndependentPromptAndReadOnlySearchTools() throws Exception {
        CapturingLlm llm = new CapturingLlm();
        PlannerAgent planner = new PlannerAgent(llm, null);

        ExecutionPlan plan = planner.plan("add feature");

        assertEquals(1, plan.tasks().size());
        assertTrue(String.valueOf(llm.messages.getFirst().get("content")).contains("Planner Agent"));
        assertTrue(String.valueOf(llm.messages.get(1).get("content")).contains("当前日期时间："));
        assertTrue(String.valueOf(llm.messages.get(1).get("content")).contains("当前工作目录："));
        Set<String> toolNames = llm.tools.stream()
                .map(t -> (Map<?, ?>) t.get("function"))
                .map(fn -> String.valueOf(fn.get("name")))
                .collect(Collectors.toSet());
        assertEquals(Set.of("Read", "LS", "Glob", "Grep", "WebFetch", "WebSearch"), toolNames);
        assertEquals(List.of("Read", "LS", "Glob", "Grep", "WebFetch", "WebSearch"), planner.toolNames());
    }

    @Test
    void fallsBackToExecutableDagWhenModelNeverReturnsJson() throws Exception {
        PlannerAgent planner = new PlannerAgent(new NonJsonLlm(), null);

        ExecutionPlan plan = planner.plan("write new.md");

        assertEquals(3, plan.tasks().size());
        assertEquals(TaskType.PLANNING, plan.task("T1").type());
        assertEquals(TaskType.FILE_WRITE, plan.task("T2").type());
        assertEquals(List.of("T1"), plan.task("T2").dependencies());
        assertTrue(plan.task("T1").description().contains("保底计划"));
    }

    @Test
    void reportsPlannerToolCallsToCliCallback() throws Exception {
        ToolCallingLlm llm = new ToolCallingLlm();
        PlannerAgent planner = new PlannerAgent(llm, null);
        List<String> calls = new ArrayList<>();

        planner.plan("inspect files", (name, args) -> calls.add(name + ":" + args.get("path")));

        assertEquals(List.of("LS:/tmp"), calls);
    }

    private static final class CapturingLlm extends LlmClient {
        private List<Map<String, Object>> messages;
        private List<Map<String, Object>> tools;

        private CapturingLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            this.messages = messages;
            this.tools = tools;
            return new Response("{\"tasks\":[{\"id\":\"T1\",\"description\":\"read\",\"type\":\"FILE_READ\",\"dependencies\":[]}]}", "", List.of(), 0, 0, 0);
        }
    }

    private static final class NonJsonLlm extends LlmClient {
        private NonJsonLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            return new Response("我会先分析，然后再规划。", "", List.of(), 0, 0, 0);
        }
    }

    private static final class ToolCallingLlm extends LlmClient {
        private int calls;

        private ToolCallingLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            calls++;
            if (calls == 1) {
                ToolCall toolCall = new ToolCall("call_ls", "LS", Map.of("path", "/tmp"));
                if (onToolReady != null) onToolReady.accept(0, toolCall);
                return new Response("", "", List.of(toolCall), 0, 0, 0);
            }
            return new Response("{\"tasks\":[{\"id\":\"T1\",\"description\":\"done\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}", "", List.of(), 0, 0, 0);
        }
    }
}
