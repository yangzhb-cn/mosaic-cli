package com.yang.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleStoreTest {
    @TempDir
    Path temp;

    @Test
    void emptyStoreListsNoTasks() throws Exception {
        ScheduleStore store = new ScheduleStore(temp.resolve("data"));

        assertTrue(store.list().isEmpty());
    }

    @Test
    void createAndUpdateWritesJsonFile() throws Exception {
        ScheduleStore store = new ScheduleStore(temp.resolve("data"));
        ScheduledTask task = store.create("say hi", "once", Instant.now().plusSeconds(60).toString(), null, "chat-1");

        assertTrue(Files.exists(temp.resolve("data/schedule/tasks.json")));
        assertEquals(1, store.list().size());
        assertTrue(store.pause(task.id()));
        ScheduledTask paused = store.load(task.id());
        assertEquals(ScheduledTask.STATUS_PAUSED, paused.status());
        assertEquals("chat-1", paused.chatId());
    }

    @Test
    void completedAndCanceledTasksAreNotDue() throws Exception {
        ScheduleStore store = new ScheduleStore(temp.resolve("data"));
        ScheduledTask canceled = store.create("cancel", "once", Instant.now().minusSeconds(5).toString(), null, "");
        ScheduledTask completed = store.create("complete", "once", Instant.now().minusSeconds(5).toString(), null, "");

        assertEquals(2, store.dueTasks(Instant.now()).size());

        assertTrue(store.cancel(canceled.id()));
        store.finishRun(completed, true, "ok", "", Instant.now());

        assertTrue(store.dueTasks(Instant.now()).isEmpty());
    }
}
