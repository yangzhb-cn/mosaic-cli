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
        List<String> progress = new ArrayList<>();

        new PlanRunner((task, ignored, onProgress) -> {
            order.add(task.id());
            return "ok";
        }).run(plan, progress::add);

        assertEquals(List.of("T1", "T2"), order);
        assertTrue(plan.allCompleted());
        assertTrue(progress.stream().anyMatch(s -> s.contains("🔧 SubAgent(") && s.contains("id=T1") && s.contains("task=first")));
        assertTrue(progress.stream().anyMatch(s -> s.contains("T2") && s.contains("完成")));
    }

    @Test
    void runsReadyTasksInParallel() throws Exception {
        ExecutionPlan plan = new ExecutionPlan("task", List.of(
                new PlanTask("T1", "one", TaskType.FILE_READ, List.of()),
                new PlanTask("T2", "two", TaskType.ANALYSIS, List.of())
        ));
        CountDownLatch started = new CountDownLatch(2);

        PlanRunResult result = new PlanRunner((task, ignored, onProgress) -> {
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

        new PlanRunner((task, ignored, onProgress) -> {
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

        PlanRunResult result = new PlanRunner((task, ignored, onProgress) -> {
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

    @Test
    void subAgentPromptIncludesResearchBudgetAndDateRules() throws Exception {
        CapturingAgent agent = new CapturingAgent();
        ExecutionPlan plan = new ExecutionPlan("查看今天的热点AI新闻", List.of(
                new PlanTask("T1", "搜索2026年5月27日热点AI新闻", TaskType.ANALYSIS, List.of())
        ));

        new PlanRunner(agent).run(plan);

        assertTrue(agent.task.contains("搜索和外部资料任务"));
        assertTrue(agent.task.contains("外部资料、当前信息、文档、网页、竞品、趋势、新闻或热点"));
        assertTrue(agent.task.contains("拿到足够来源后立刻停止搜索并整理结果"));
        assertTrue(agent.task.contains("已经包含明确日期时，不要再调用日期/时间工具"));
        assertTrue(agent.task.contains("不要把旧信息、相邻日期信息或弱相关页面伪装成用户要求的时间、主题或结论"));
        assertEquals(8, agent.maxRounds);
    }

    private static final class CapturingAgent extends com.yang.agent.Agent {
        private String task;
        private int maxRounds;

        private CapturingAgent() {
            super(new com.yang.llm.LlmClient("test-model", "key", "http://localhost", 0), 128000);
        }

        @Override
        public String runSubAgent(String task, int maxRounds, java.util.function.BiConsumer<String, java.util.Map<String, Object>> onTool) {
            this.task = task;
            this.maxRounds = maxRounds;
            return "ok";
        }
    }
}
