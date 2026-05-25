package com.coder.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record McpConfig(boolean configured, List<Server> servers) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Server(String name, String type, String command, List<String> args, Map<String, String> env,
                         String url, String endpoint, Map<String, String> headers) {
        public Server(String name, String command, List<String> args, Map<String, String> env) {
            this(name, "", command, args, env, "", "", Map.of());
        }
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".mosaiccoder", "mcp.json");
    }

    public static McpConfig loadDefault() {
        return load(defaultPath());
    }

    public static McpConfig load(Path file) {
        if (!Files.isRegularFile(file)) return new McpConfig(false, List.of());
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            JsonNode servers = root.path("mcpServers");
            if (!servers.isObject()) return new McpConfig(true, List.of());
            List<Server> out = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = servers.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode node = entry.getValue();
                out.add(new Server(
                        entry.getKey(),
                        node.path("type").asText(""),
                        node.path("command").asText(""),
                        strings(node.path("args")),
                        stringMap(node.path("env")),
                        node.path("url").asText(""),
                        node.path("endpoint").asText(""),
                        stringMap(node.path("headers"))
                ));
            }
            return new McpConfig(true, out);
        } catch (IOException e) {
            return new McpConfig(true, List.of());
        }
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) out.add(item.asText());
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }
}
