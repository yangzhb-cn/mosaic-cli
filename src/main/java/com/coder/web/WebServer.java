package com.coder.web;

import com.coder.Config;
import com.coder.LlmClient;
import com.coder.Main;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class WebServer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Config config;
    private final Path launchCwd;
    private final WebSessionHub sessions;
    private final FileBrowser files;
    private HttpServer server;

    public WebServer(Config config, LlmClient llm, Path launchCwd) {
        this.config = config;
        this.launchCwd = launchCwd.toAbsolutePath().normalize();
        this.sessions = new WebSessionHub(config, llm);
        this.files = new FileBrowser(this.launchCwd);
    }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        int actualPort = server.getAddress().getPort();
        System.out.println("🌐 CoreCoder Web API 已启动: http://localhost:" + actualPort);
        return actualPort;
    }

    public void stop(int delaySeconds) {
        if (server != null) server.stop(delaySeconds);
    }

    private void handle(HttpExchange exchange) throws IOException {
        addCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            route(exchange);
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 404, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();

        if ("GET".equals(method) && "/api/health".equals(path)) {
            writeJson(exchange, 200, Map.of("ok", true, "version", Main.VERSION));
            return;
        }
        if ("GET".equals(method) && "/api/home".equals(path)) {
            writeJson(exchange, 200, Map.of(
                    "home", System.getProperty("user.home"),
                    "cwd", launchCwd.toString()
            ));
            return;
        }
        if ("GET".equals(method) && "/api/models".equals(path)) {
            writeJson(exchange, 200, Map.of(
                    "model", config.model,
                    "baseUrl", config.baseUrl,
                    "temperature", config.temperature,
                    "maxContextTokens", config.maxContextTokens
            ));
            return;
        }
        if ("GET".equals(method) && "/api/sessions".equals(path)) {
            writeJson(exchange, 200, Map.of("sessions", sessions.list()));
            return;
        }
        if ("POST".equals(method) && ("/api/sessions".equals(path) || "/api/agent/new".equals(path))) {
            writeJson(exchange, 200, Map.of("session", createSession(exchange)));
            return;
        }
        if (path.startsWith("/api/files/")) {
            handleFiles(exchange, method, path);
            return;
        }
        if (path.startsWith("/api/agent/")) {
            handleAgent(exchange, method, path);
            return;
        }

        writeJson(exchange, 404, Map.of("error", "not found"));
    }

    private Map<String, Object> createSession(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String rawCwd = String.valueOf(body.getOrDefault("cwd", launchCwd.toString()));
        Path cwd = Path.of(rawCwd).toAbsolutePath().normalize();
        if (!Files.isDirectory(cwd)) throw new IOException("cwd is not a directory");
        if (!files.isAllowed(cwd.toString())) throw new IOException("cwd is outside the allowed roots");
        return sessions.create(cwd);
    }

    private void handleFiles(HttpExchange exchange, String method, String path) throws IOException {
        if (!"GET".equals(method)) {
            writeJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }
        String rawPath = decode(path.substring("/api/files/".length()));
        String type = query(exchange.getRequestURI()).getOrDefault("type", "list");
        if ("read".equals(type)) {
            writeJson(exchange, 200, files.read(rawPath));
            return;
        }
        if ("list".equals(type)) {
            writeJson(exchange, 200, files.list(rawPath));
            return;
        }
        writeJson(exchange, 400, Map.of("error", "unknown file request type"));
    }

    private void handleAgent(HttpExchange exchange, String method, String path) throws IOException {
        String rest = path.substring("/api/agent/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            writeJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        String id = decode(rest.substring(0, slash));
        String action = rest.substring(slash + 1);

        if ("GET".equals(method) && "events".equals(action)) {
            if (!sessions.has(id)) {
                writeJson(exchange, 404, Map.of("error", "unknown session"));
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            sessions.subscribe(id, new SseClient(exchange.getResponseBody()));
            return;
        }

        if ("POST".equals(method) && "messages".equals(action)) {
            Map<String, Object> body = readJson(exchange);
            String message = String.valueOf(body.getOrDefault("message", "")).strip();
            if (message.isBlank()) {
                writeJson(exchange, 400, Map.of("error", "message is required"));
                return;
            }
            sessions.sendMessage(id, message);
            writeJson(exchange, 202, Map.of("accepted", true));
            return;
        }

        writeJson(exchange, 404, Map.of("error", "not found"));
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) return Map.of();
        return JSON.readValue(bytes, new TypeReference<>() {});
    }

    private void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "content-type");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            int idx = part.indexOf('=');
            if (idx < 0) {
                values.put(decode(part), "");
            } else {
                values.put(decode(part.substring(0, idx)), decode(part.substring(idx + 1)));
            }
        }
        return values;
    }
}
