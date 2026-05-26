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
        assertTrue(act.contains("COMPLETED"));
        assertTrue(act.contains("任务结果"));
        assertTrue(act.contains("ok"));
        assertFalse(controller.isActive());
        assertTrue(progress.stream().anyMatch(s -> s.contains("🔧 SubAgent(") && s.contains("id=T1")));
        assertTrue(progress.stream().anyMatch(s -> s.contains("T1") && s.contains("完成")));
        assertEquals(2, changed.get());

        assertEquals("❌ 已取消当前计划。", controller.cancel());
        assertFalse(controller.isActive());
    }

    @Test
    void planningFailureDoesNotCrashOrKeepPlanActive() throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        AtomicInteger changed = new AtomicInteger();
        CliPlanController controller = new CliPlanController(
                new PlannerAgent(new InvalidPlanLlm(), null),
                new PlanRunner((task, plan) -> "ok"),
                messages,
                changed::incrementAndGet
        );
        controller.enter();

        String response = controller.chatCli("bad plan", null, null, (input, onToken, onTool) -> {
            fail("fallback chat should not run while awaiting plan task");
            return "";
        });

        assertTrue(response.contains("规划失败"));
        assertFalse(controller.isActive());
        assertEquals("bad plan", messages.getFirst().get("content"));
        assertEquals(2, messages.size());
        assertEquals(1, changed.get());
    }

    private static CliPlanController controller(List<Map<String, Object>> messages, AtomicInteger changed) {
        PlannerAgent planner = new PlannerAgent(new PlanningLlm(), null);
        PlanRunner runner = new PlanRunner((task, plan) -> "ok");
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
}
