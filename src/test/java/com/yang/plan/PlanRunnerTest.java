package com.yang.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlanRunnerTest {
    @Test
    void respectsDependencies() throws Exception {
        ExecutionPlan plan = new ExecutionPlan("task", List.of(
                new PlanTask("T1", "first", TaskType.FILE_READ, List.of()),
                new PlanTask("T2", "second", TaskType.ANALYSIS, List.of("T1"))
        ));
        List<String> order = new ArrayList<>();

        new PlanRunner((task, ignored) -> {
            order.add(task.id());
            return "ok";
        }).run(plan);

        assertEquals(List.of("T1", "T2"), order);
        assertTrue(plan.allCompleted());
    }

    @Test
    void runsReadyTasksInParallel() throws Exception {
        ExecutionPlan plan = new ExecutionPlan("task", List.of(
                new PlanTask("T1", "one", TaskType.FILE_READ, List.of()),
                new PlanTask("T2", "two", TaskType.ANALYSIS, List.of())
        ));
        CountDownLatch started = new CountDownLatch(2);

        PlanRunResult result = new PlanRunner((task, ignored) -> {
            started.countDown();
            if (!started.await(1, TimeUnit.SECONDS)) return "错误: not parallel";
            return "ok";
        }, 2).run(plan);

        assertTrue(result.success());
        assertTrue(plan.allCompleted());
    }

    @Test
    void serializesWriteAndCommandTasks() throws Exception {
        ExecutionPlan plan = new ExecutionPlan("task", List.of(
                new PlanTask("T1", "write", TaskType.FILE_WRITE, List.of()),
                new PlanTask("T2", "command", TaskType.COMMAND, List.of())
        ));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();

        new PlanRunner((task, ignored) -> {
            int now = active.incrementAndGet();
            max.updateAndGet(old -> Math.max(old, now));
            Thread.sleep(80);
            active.decrementAndGet();
            return "ok";
        }, 2).run(plan);

        assertEquals(1, max.get());
    }

    @Test
    void retriesFailureThreeTimesAndLeavesLaterTasksPending() throws Exception {
        ExecutionPlan plan = new ExecutionPlan("task", List.of(
                new PlanTask("T0", "complete", TaskType.FILE_READ, List.of()),
                new PlanTask("T1", "fail", TaskType.ANALYSIS, List.of()),
                new PlanTask("T2", "later", TaskType.VERIFICATION, List.of("T1"))
        ));

        PlanRunResult result = new PlanRunner((task, ignored) -> {
            if (task.id().equals("T1")) return "错误: boom";
            return "ok";
        }, 2).run(plan);

        assertFalse(result.success());
        assertEquals(TaskStatus.COMPLETED, plan.task("T0").status());
        assertEquals(TaskStatus.FAILED, plan.task("T1").status());
        assertEquals(3, plan.task("T1").attempts());
        assertEquals(TaskStatus.PENDING, plan.task("T2").status());
        assertTrue(result.failure().contains("T1 失败"));
    }
}
