package com.corecoder.web;

import com.corecoder.Agent;
import com.corecoder.Config;
import com.corecoder.LlmClient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class WebSessionHub {
    private final Config config;
    private final LlmClient llm;
    private final ConcurrentMap<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    WebSessionHub(Config config, LlmClient llm) {
        this.config = config;
        this.llm = llm;
    }

    Map<String, Object> create(Path cwd) {
        String id = UUID.randomUUID().toString();
        WebSession session = new WebSession(id, cwd.toAbsolutePath().normalize(), new Agent(llm, config.maxContextTokens));
        sessions.put(id, session);
        return session.summary(llm);
    }

    List<Map<String, Object>> list() {
        return sessions.values().stream()
                .sorted((a, b) -> b.modified.compareTo(a.modified))
                .map(s -> s.summary(llm))
                .toList();
    }

    boolean has(String id) {
        return sessions.containsKey(id);
    }

    void sendMessage(String id, String message) {
        WebSession session = require(id);
        if (!session.running.compareAndSet(false, true)) {
            throw new IllegalStateException("session is already running");
        }
        session.touch();
        if (session.firstMessage.isBlank()) {
            session.firstMessage = message.length() > 80 ? message.substring(0, 80) : message;
        }
        executor.submit(() -> {
            try {
                String response = session.agent.chat(message,
                        token -> session.publish("token", Map.of("text", token)),
                        (name, args) -> session.publish("tool", Map.of("name", name, "args", args)));
                session.touch();
                session.publish("done", Map.of(
                        "message", response,
                        "session", session.summary(llm)
                ));
            } catch (Exception e) {
                session.publish("error", Map.of("message", e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                session.running.set(false);
            }
        });
    }

    void subscribe(String id, SseClient client) {
        WebSession session = require(id);
        session.clients.add(client);
        client.send("ready", session.summary(llm));
        session.replay(client);
        try {
            while (client.isOpen()) {
                Thread.sleep(15_000);
                client.send("ping", Map.of("time", Instant.now().toString()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            session.clients.remove(client);
            client.close();
        }
    }

    private WebSession require(String id) {
        WebSession session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("unknown session");
        return session;
    }

    private static final class WebSession {
        private final String id;
        private final Path cwd;
        private final Agent agent;
        private final Instant created = Instant.now();
        private final CopyOnWriteArraySet<SseClient> clients = new CopyOnWriteArraySet<>();
        private final List<WebEvent> history = new ArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private volatile Instant modified = created;
        private volatile String firstMessage = "";

        private WebSession(String id, Path cwd, Agent agent) {
            this.id = id;
            this.cwd = cwd;
            this.agent = agent;
        }

        private void touch() {
            modified = Instant.now();
        }

        private void publish(String event, Object data) {
            synchronized (history) {
                history.add(new WebEvent(event, data));
                if (history.size() > 200) history.remove(0);
            }
            for (SseClient client : new ArrayList<>(clients)) {
                client.send(event, data);
                if (!client.isOpen()) clients.remove(client);
            }
        }

        private void replay(SseClient client) {
            List<WebEvent> copy;
            synchronized (history) {
                copy = List.copyOf(history);
            }
            for (WebEvent event : copy) {
                client.send(event.name(), event.data());
            }
        }

        private Map<String, Object> summary(LlmClient llm) {
            return Map.of(
                    "id", id,
                    "cwd", cwd.toString(),
                    "created", created.toString(),
                    "modified", modified.toString(),
                    "messageCount", agent.messages.size(),
                    "firstMessage", firstMessage,
                    "running", running.get(),
                    "tokens", Map.of(
                            "input", llm.totalPromptTokens,
                            "output", llm.totalCompletionTokens
                    )
            );
        }
    }

    private record WebEvent(String name, Object data) {}
}
