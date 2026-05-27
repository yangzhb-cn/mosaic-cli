package com.yang.schedule;

import com.yang.agent.Agent;
import com.yang.audit.ToolAudit;
import com.yang.llm.LlmClient;
import com.yang.memory.MemoryManager;
import com.yang.session.SessionManager;
import com.yang.tool.Tools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleToolsTest {
    @TempDir
    Path temp;

    @Test
    void scheduleTaskValidatesRequiredTimingArgs() {
        Agent agent = agent(new ScheduleStore(temp.resolve("data")));
        Tools.Tool schedule = Tools.get(Tools.all(agent), "schedule_task");

        assertTrue(schedule.execute(Map.of("prompt", "hi", "schedule_type", "once")).contains("run_at"));
        assertTrue(schedule.execute(Map.of("prompt", "hi", "schedule_type", "interval")).contains("interval_seconds"));
    }

    @Test
    void listPauseResumeAndCancelTasks() throws Exception {
        ScheduleStore store = new ScheduleStore(temp.resolve("data"));
        Agent agent = agent(store);
        List<Tools.Tool> tools = Tools.all(agent);

        String created = Tools.get(tools, "schedule_task").execute(Map.of(
                "prompt", "check status",
                "schedule_type", "once",
                "run_at", Instant.now().plusSeconds(60).toString()
        ));
        assertTrue(created.contains("已创建计划任务"));
        ScheduledTask task = store.list().getFirst();

        assertTrue(Tools.get(tools, "list_scheduled_tasks").execute(Map.of()).contains("check status"));
        assertTrue(Tools.get(tools, "pause_scheduled_task").execute(Map.of("id", task.id())).contains("已暂停"));
        assertEquals(ScheduledTask.STATUS_PAUSED, store.load(task.id()).status());

        assertTrue(Tools.get(tools, "resume_scheduled_task").execute(Map.of("id", task.id())).contains("已恢复"));
        assertEquals(ScheduledTask.STATUS_ACTIVE, store.load(task.id()).status());

        assertTrue(Tools.get(tools, "cancel_scheduled_task").execute(Map.of("id", task.id())).contains("已取消"));
        assertEquals(ScheduledTask.STATUS_CANCELED, store.load(task.id()).status());
    }

    private static Agent agent(ScheduleStore store) {
        return new Agent(
                new LlmClient("test-model", "key", "http://localhost", 0),
                128000,
                null,
                List.of(),
                List.of(),
                new ToolAudit(),
                MemoryManager.disabled(),
                SessionManager.disabled(),
                store
        );
    }
}
