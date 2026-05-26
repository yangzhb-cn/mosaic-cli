package com.yang.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanParserTest {
    @Test
    void parsesStrictJson() {
        ExecutionPlan plan = PlanParser.parse("task", """
                {"tasks":[
                  {"id":"T1","description":"read files","type":"FILE_READ","dependencies":[]},
                  {"id":"T2","description":"write change","type":"FILE_WRITE","dependencies":["T1"]}
                ]}
                """);

        assertEquals("task", plan.task());
        assertEquals(2, plan.tasks().size());
        assertEquals(TaskType.FILE_READ, plan.tasks().getFirst().type());
        assertEquals("T1", plan.tasks().get(1).dependencies().getFirst());
    }

    @Test
    void parsesJsonCodeBlock() {
        ExecutionPlan plan = PlanParser.parse("task", """
                ```json
                {"tasks":[{"id":"T1","description":"verify","type":"VERIFICATION","dependencies":[]}]}
                ```
                """);

        assertEquals("T1", plan.tasks().getFirst().id());
    }

    @Test
    void rejectsDuplicateIds() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PlanParser.parse("task", """
                {"tasks":[
                  {"id":"T1","description":"a","type":"ANALYSIS","dependencies":[]},
                  {"id":"T1","description":"b","type":"ANALYSIS","dependencies":[]}
                ]}
                """));

        assertTrue(error.getMessage().contains("重复任务 id"));
    }

    @Test
    void rejectsUnknownDependency() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PlanParser.parse("task", """
                {"tasks":[{"id":"T1","description":"a","type":"ANALYSIS","dependencies":["missing"]}]}
                """));

        assertTrue(error.getMessage().contains("依赖不存在"));
    }

    @Test
    void rejectsCycle() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PlanParser.parse("task", """
                {"tasks":[
                  {"id":"T1","description":"a","type":"ANALYSIS","dependencies":["T2"]},
                  {"id":"T2","description":"b","type":"ANALYSIS","dependencies":["T1"]}
                ]}
                """));

        assertTrue(error.getMessage().contains("环形依赖"));
    }
}
