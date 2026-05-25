package com.coder.mcp;

import com.coder.tools.Tools;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class McpManager implements AutoCloseable {
    private static final io.modelcontextprotocol.json.McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final boolean configured;
    private final List<ServerStatus> servers;
    private final List<Tools.Tool> tools;
    private final List<McpSyncClient> clients;

    public record ServerStatus(String name, boolean loaded, int toolCount, String error) {
    }

    private McpManager(boolean configured, List<ServerStatus> servers, List<Tools.Tool> tools, List<McpSyncClient> clients) {
        this.configured = configured;
        this.servers = List.copyOf(servers);
        this.tools = List.copyOf(tools);
        this.clients = List.copyOf(clients);
    }

    public static McpManager empty() {
        return new McpManager(false, List.of(), List.of(), List.of());
    }

    public static McpManager loadDefault(Set<String> usedNames) {
        return load(McpConfig.loadDefault(), usedNames);
    }

    public static McpManager load(McpConfig config, Set<String> usedNames) {
        if (!config.configured()) return empty();
        Set<String> names = new LinkedHashSet<>(usedNames);
        List<ServerStatus> statuses = new ArrayList<>();
        List<Tools.Tool> tools = new ArrayList<>();
        List<McpSyncClient> clients = new ArrayList<>();
        for (McpConfig.Server server : config.servers()) {
            String transportType = type(server);
            if (!supported(transportType)) {
                statuses.add(new ServerStatus(server.name(), false, 0, "不支持的 MCP type: " + transportType));
                continue;
            }
            if ("stdio".equals(transportType) && server.command().isBlank()) {
                statuses.add(new ServerStatus(server.name(), false, 0, "command 为空"));
                continue;
            }
            if (("http".equals(transportType) || "streamable-http".equals(transportType) || "sse".equals(transportType)) && server.url().isBlank()) {
                statuses.add(new ServerStatus(server.name(), false, 0, transportType + " url 为空"));
                continue;
            }
            McpSyncClient client = null;
            try {
                McpClientTransport transport = transport(server, transportType);
                client = McpClient.sync(transport)
                        .clientInfo(new Implementation("mosaiccoder", "0.1.0"))
                        .initializationTimeout(Duration.ofSeconds(10))
                        .requestTimeout(Duration.ofSeconds(60))
                        .build();
                client.initialize();
                List<io.modelcontextprotocol.spec.McpSchema.Tool> listed = client.listTools().tools();
                for (io.modelcontextprotocol.spec.McpSchema.Tool tool : listed) {
                    tools.add(new McpTool(unique(toolName(server.name(), tool.name()), names), server.name(), client, tool));
                }
                clients.add(client);
                statuses.add(new ServerStatus(server.name(), true, listed.size(), ""));
            } catch (Exception e) {
                if (client != null) client.closeGracefully();
                statuses.add(new ServerStatus(server.name(), false, 0, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return new McpManager(true, statuses, tools, clients);
    }

    private static McpClientTransport transport(McpConfig.Server server, String type) {
        return switch (type) {
            case "http", "streamable-http" -> {
                var builder = HttpClientStreamableHttpTransport.builder(server.url())
                        .jsonMapper(JSON_MAPPER)
                        .endpoint(server.endpoint().isBlank() ? "/mcp" : server.endpoint());
                if (!server.headers().isEmpty()) builder.customizeRequest(req -> server.headers().forEach(req::header));
                yield builder.build();
            }
            case "sse" -> {
                var builder = HttpClientSseClientTransport.builder(server.url())
                        .jsonMapper(JSON_MAPPER)
                        .sseEndpoint(server.endpoint().isBlank() ? "/sse" : server.endpoint());
                if (!server.headers().isEmpty()) builder.customizeRequest(req -> server.headers().forEach(req::header));
                yield builder.build();
            }
            default -> {
                ServerParameters params = ServerParameters.builder(server.command())
                        .args(server.args())
                        .env(server.env())
                        .build();
                yield new StdioClientTransport(params, JSON_MAPPER);
            }
        };
    }

    private static String type(McpConfig.Server server) {
        String type = server.type() == null ? "" : server.type().trim().toLowerCase();
        if (!type.isBlank()) return type;
        return server.url().isBlank() ? "stdio" : "http";
    }

    private static boolean supported(String type) {
        return "stdio".equals(type) || "http".equals(type) || "streamable-http".equals(type) || "sse".equals(type);
    }

    public List<Tools.Tool> tools() {
        return tools;
    }

    public String summary() {
        if (!configured) return "MCP: 未配置";
        long loaded = servers.stream().filter(ServerStatus::loaded).count();
        List<ServerStatus> failed = servers.stream().filter(s -> !s.loaded()).toList();
        String text = "MCP: " + loaded + " servers, " + tools.size() + " tools";
        if (failed.isEmpty()) return text;
        if (failed.size() == 1) return text + ", 1 failed (" + failed.getFirst().name() + ": " + failed.getFirst().error() + ")";
        return text + ", " + failed.size() + " failed";
    }

    public String details() {
        if (!configured) return "MCP: 未配置 (" + McpConfig.defaultPath() + ")";
        if (servers.isEmpty()) return "MCP: 已配置，但没有 server";
        StringBuilder out = new StringBuilder();
        for (ServerStatus server : servers) {
            if (server.loaded()) {
                out.append("✅ ").append(server.name()).append(": ").append(server.toolCount()).append(" tools\n");
            } else {
                out.append("❌ ").append(server.name()).append(": ").append(server.error()).append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    @Override
    public void close() {
        for (McpSyncClient client : clients) client.closeGracefully();
    }

    static String toolName(String server, String tool) {
        return "mcp_" + sanitize(server) + "_" + sanitize(tool);
    }

    private static String sanitize(String value) {
        String s = value.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        if (s.isBlank()) return "tool";
        if (Character.isDigit(s.charAt(0))) return "_" + s;
        return s;
    }

    static String unique(String base, Set<String> used) {
        String name = base;
        for (int i = 2; !used.add(name); i++) name = base + "_" + i;
        return name;
    }
}
