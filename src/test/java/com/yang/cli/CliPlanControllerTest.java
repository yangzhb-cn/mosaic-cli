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
        CliPlanController controller = controller(messages, changed);

        controller.enter();
        controller.chatCli("do work", null, null, (input, onToken, onTool) -> "");
        String act = controller.act();

        assertTrue(act.contains("计划执行完成"));
        assertTrue(act.contains("COMPLETED"));
        assertTrue(controller.session().ready());
        assertEquals(2, changed.get());

        assertEquals("已取消当前计划。", controller.cancel());
        assertFalse(controller.isActive());
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
}
