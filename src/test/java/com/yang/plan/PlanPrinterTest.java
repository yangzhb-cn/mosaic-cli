package com.yang.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanPrinterTest {
    @Test
    void truncatesLongDescriptionsToKeepDagTableSingleLinePerTask() {
        ExecutionPlan plan = new ExecutionPlan("task", List.of(
                new PlanTask("T1", "这是一个非常长的任务描述，用来验证 DAG 表格不会因为描述过长而换行破坏对齐。后面还有很多细节。", TaskType.PLANNING, List.of()),
                new PlanTask("T2", "short", TaskType.VERIFICATION, List.of("T1"))
        ));

        List<String> lines = PlanPrinter.table(plan).lines().toList();

        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("..."));
        assertTrue(lines.get(2).contains("short"));
        assertEquals(lines.get(1).indexOf("PENDING"), lines.get(2).indexOf("PENDING"));
    }
}
