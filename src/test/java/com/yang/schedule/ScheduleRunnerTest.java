package com.yang.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yang.agent.Agent;
import com.yang.im.ImClient;
import com.yang.im.ImMessage;
import com.yang.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void dueOnceTaskCompletesAndNotifiesOriginalChat() throws Exception {
        ScheduleStore store = new ScheduleStore(temp.resolve("data"));
        ScheduledTask task = store.create("say hi", "once", Instant.now().minusSeconds(1).toString(), null, "chat-1");
        FakeIm im = new FakeIm();
        CapturingAgent agent = new CapturingAgent(im, "done");

        new ScheduleRunner(store, agent, 30).runDueTasks();

        ScheduledTask saved = store.load(task.id());
        assertEquals(ScheduledTask.STATUS_COMPLETED, saved.status());
        assertEquals("done", saved.lastResult());
        assertEquals("", saved.lastError());
        assertEquals("chat-1", im.chatId);
        assertEquals("done", im.text);
        assertTrue(agent.task.contains("后台计划任务"));
        assertEquals(12, agent.maxRounds);
    }

    @Test
    void dueIntervalTaskStaysActiveAndUpdatesNextRun() throws Exception {
        ScheduleStore store = new ScheduleStore(temp.resolve("data"));
        ScheduledTask task = new ScheduledTask("task_1", "poll", ScheduledTask.TYPE_INTERVAL, "", 60,
                ScheduledTask.STATUS_ACTIVE, "", Instant.now().toString(), Instant.now().toString(), "",
                Instant.now().minusSeconds(1).toString(), "", "");
        writeTasks(temp.resolve("data"), List.of(task));
        CapturingAgent agent = new CapturingAgent(null, "ok");

        new ScheduleRunner(store, agent, 30).runDueTasks();

        ScheduledTask saved = store.load(task.id());
        assertEquals(ScheduledTask.STATUS_ACTIVE, saved.status());
        assertEquals("ok", saved.lastResult());
        assertTrue(Instant.parse(saved.nextRun()).isAfter(Instant.now().minusSeconds(5)));
    }

    @Test
    void failureRecordsLastErrorWithoutThrowing() throws Exception {
        ScheduleStore store = new ScheduleStore(temp.resolve("data"));
        ScheduledTask task = store.create("fail", "once", Instant.now().minusSeconds(1).toString(), null, "");
        CapturingAgent agent = new CapturingAgent(null, "错误: boom");

        new ScheduleRunner(store, agent, 30).runDueTasks();

        ScheduledTask saved = store.load(task.id());
        assertEquals(ScheduledTask.STATUS_COMPLETED, saved.status());
        assertEquals("", saved.lastResult());
        assertEquals("错误: boom", saved.lastError());
    }

    @Test
    void cliTaskDoesNotNotify() throws Exception {
        ScheduleStore store = new ScheduleStore(temp.resolve("data"));
        store.create("silent", "once", Instant.now().minusSeconds(1).toString(), null, "");
        FakeIm im = new FakeIm();

        new ScheduleRunner(store, new CapturingAgent(im, "done"), 30).runDueTasks();

        assertNull(im.chatId);
        assertNull(im.text);
    }

    private static void writeTasks(Path dataDir, List<ScheduledTask> tasks) throws Exception {
        Path file = dataDir.resolve("schedule/tasks.json");
        Files.createDirectories(file.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), tasks);
    }

    private static final class CapturingAgent extends Agent {
        private String task;
        private int maxRounds;
        private final String result;

        private CapturingAgent(ImClient im, String result) {
            super(new LlmClient("test-model", "key", "http://localhost", 0), 128000, im);
            this.result = result;
        }

        @Override
        public String runSubAgent(String task, int maxRounds, BiConsumer<String, Map<String, Object>> onTool) {
            this.task = task;
            this.maxRounds = maxRounds;
            return result;
        }
    }

    private static final class FakeIm implements ImClient {
        private String chatId;
        private String text;

        @Override
        public void start(Consumer<ImMessage> handler) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void send(String chatId, String text) {
            this.chatId = chatId;
            this.text = text;
        }

        @Override
        public void typing(String chatId) {
        }
    }
}
