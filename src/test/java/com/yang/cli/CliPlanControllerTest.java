package com.yang.cli;

import com.yang.llm.LlmClient;
import com.yang.plan.PlanRunner;
import com.yang.plan.PlannerAgent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CliPlanControllerTest {
    @Test
    void createsPlanFromAwaitingCliInputWithoutCallingFallback() throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        AtomicInteger changed = new AtomicInteger();
        CliPlanController controller = controller(messages, changed);
        controller.enter();

        String response = controller.chatCli("do work", null, null, (input, onToken, onTool) -> {
            fail("fallback chat should not run while awaiting plan task");
            return "";
        });

        assertTrue(response.contains("T1"));
        assertTrue(controller.session().ready());
        assertEquals("do work", messages.getFirst().get("content"));
        assertEquals(2, messages.size());
        assertEquals(1, changed.get());
    }

    @Test
    void actsAndCancelsPlan() throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        AtomicInteger changed = new AtomicInteger();
        List<String> progress = new ArrayList<>();
        CliPlanController controller = controller(messages, changed);

        controller.enter();
        controller.chatCli("do work", null, null, (input, onToken, onTool) -> "");
        String act = controller.act(progress::add);

        assertTrue(act.contains("计划执行完成"));
        assertTrue(act.contains("执行总结"));
        assertTrue(act.contains("任务：完成 1，失败 0，未执行 0"));
        assertTrue(act.contains("结果：ok"));
        assertTrue(act.contains("COMPLETED"));
        assertTrue(act.contains("任务结果"));
        assertTrue(act.contains("ok"));
        assertFalse(controller.isActive());
        assertTrue(progress.stream().anyMatch(s -> s.equals("▶ T1 FILE_READ read files")));
        assertTrue(progress.stream().anyMatch(s -> s.equals("✅ T1 FILE_READ read files 完成")));
        assertEquals(2, changed.get());

        assertEquals("❌ 已取消当前计划。", controller.cancel());
        assertFalse(controller.isActive());
    }

    @Test
    void invalidPlannerOutputFallsBackToExecutablePlan() throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        AtomicInteger changed = new AtomicInteger();
        CliPlanController controller = new CliPlanController(
                new PlannerAgent(new InvalidPlanLlm(), null),
                new PlanRunner((task, plan, onProgress) -> "ok"),
                messages,
                changed::incrementAndGet
        );
        controller.enter();

        String response = controller.chatCli("bad plan", null, null, (input, onToken, onTool) -> {
            fail("fallback chat should not run while awaiting plan task");
            return "";
        });

        assertTrue(response.contains("T1"));
        assertTrue(response.contains("保底计划"));
        assertTrue(controller.isActive());
        assertEquals("bad plan", messages.getFirst().get("content"));
        assertEquals(2, messages.size());
        assertEquals(1, changed.get());
    }

    @Test
    void forwardsPlannerToolCallsToCliCallback() throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        AtomicInteger changed = new AtomicInteger();
        List<String> calls = new ArrayList<>();
        CliPlanController controller = new CliPlanController(
                new PlannerAgent(new ToolCallingLlm(), null),
                new PlanRunner((task, plan, onProgress) -> "ok"),
                messages,
                changed::incrementAndGet
        );
        controller.enter();

        controller.chatCli("inspect files", null, (name, args) -> calls.add(name + ":" + args.get("path")),
                (input, onToken, onTool) -> {
                    fail("fallback chat should not run while awaiting plan task");
                    return "";
                });

        assertEquals(List.of("LS:/tmp"), calls);
    }

    @Test
    void actUsesLlmSummaryWhenAvailable() throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        AtomicInteger changed = new AtomicInteger();
        SummaryLlm llm = new SummaryLlm();
        CliPlanController controller = new CliPlanController(
                new PlannerAgent(new PlanningLlm(), null),
                new PlanRunner((task, plan, onProgress) -> "ok"),
                messages,
                changed::incrementAndGet,
                llm
        );
        controller.enter();
        controller.chatCli("do work", null, null, (input, onToken, onTool) -> "");

        String act = controller.act();

        assertTrue(act.contains("LLM 总结：任务已完成"));
        assertFalse(act.contains("任务：完成 1，失败 0，未执行 0"));
        assertEquals(1, llm.summaryCalls);
    }

    private static CliPlanController controller(List<Map<String, Object>> messages, AtomicInteger changed) {
        PlannerAgent planner = new PlannerAgent(new PlanningLlm(), null);
        PlanRunner runner = new PlanRunner((task, plan, onProgress) -> "ok");
        return new CliPlanController(planner, runner, messages, changed::incrementAndGet);
    }

    private static final class PlanningLlm extends LlmClient {
        private PlanningLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            return new Response("{\"tasks\":[{\"id\":\"T1\",\"description\":\"read files\",\"type\":\"FILE_READ\",\"dependencies\":[]}]}", "", List.of(), 0, 0, 0);
        }
    }

    private static final class InvalidPlanLlm extends LlmClient {
        private InvalidPlanLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            return new Response("not json", "", List.of(), 0, 0, 0);
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

    private static final class SummaryLlm extends LlmClient {
        private int summaryCalls;

        private SummaryLlm() {
            super("test-model", "key", "http://localhost", 0);
        }

        @Override
        public Response chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken, ToolReady onToolReady) {
            summaryCalls++;
            assertTrue(String.valueOf(messages.getLast().get("content")).contains("原始目标：do work"));
            assertTrue(tools.isEmpty());
            return new Response("LLM 总结：任务已完成", "", List.of(), 0, 0, 0);
        }
    }
}
