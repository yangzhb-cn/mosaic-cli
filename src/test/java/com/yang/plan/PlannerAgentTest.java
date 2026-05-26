package com.yang.plan;

import com.yang.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Set<String> toolNames = llm.tools.stream()
                .map(t -> (Map<?, ?>) t.get("function"))
                .map(fn -> String.valueOf(fn.get("name")))
                .collect(Collectors.toSet());
        assertEquals(Set.of("Read", "LS", "Glob", "Grep", "WebFetch", "WebSearch"), toolNames);
        assertEquals(List.of("Read", "LS", "Glob", "Grep", "WebFetch", "WebSearch"), planner.toolNames());
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
}
