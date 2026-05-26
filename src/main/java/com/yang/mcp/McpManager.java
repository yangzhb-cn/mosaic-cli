package com.yang.mcp;

import com.yang.tool.Tools;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 负责启动、管理和关闭 MCP 客户端，并把 MCP tool 适配为本地工具。 */
public final class McpManager implements AutoCloseable {
    private static final io.modelcontextprotocol.json.McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final boolean configured;
    private final List<ServerStatus> servers;
    private final List<Tools.Tool> tools;
    private final List<McpSyncClient> clients;

    /** 表示单个 MCP server 的加载状态和错误信息。 */
    public record ServerStatus(String name, boolean loaded, int toolCount, String error) {
    }

    /** MCP server 启动后的客户端、工具列表和状态。 */
    private record ServerLoad(McpConfig.Server server, ServerStatus status, McpSyncClient client, List<io.modelcontextprotocol.spec.McpSchema.Tool> tools) {
    }

    /** 正在并发加载的 MCP server 任务。 */
    private record ServerTask(McpConfig.Server server, Future<ServerLoad> future) {
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
        try (var pool = serverPool(config.servers().size())) {
            List<ServerTask> tasks = new ArrayList<>();
            for (McpConfig.Server server : config.servers()) tasks.add(new ServerTask(server, pool.submit(() -> loadServer(server))));
            for (ServerTask task : tasks) {
                ServerLoad loaded;
                try {
                    loaded = task.future().get();
                } catch (Exception e) {
                    statuses.add(new ServerStatus(task.server().name(), false, 0, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    continue;
                }
                statuses.add(loaded.status());
                if (!loaded.status().loaded()) continue;
                for (io.modelcontextprotocol.spec.McpSchema.Tool tool : loaded.tools()) {
                    tools.add(new McpTool(unique(toolName(loaded.server().name(), tool.name()), names), loaded.server().name(), loaded.client(), tool));
                }
                clients.add(loaded.client());
            }
        }
        return new McpManager(true, statuses, tools, clients);
    }

    private static ExecutorService serverPool(int serverCount) {
        int threads = Math.max(1, Math.min(8, serverCount));
        int queueSize = Math.max(1, serverCount);
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private static ServerLoad loadServer(McpConfig.Server server) {
        String transportType = type(server);
        if (!supported(transportType)) {
            return failed(server, "不支持的 MCP type: " + transportType);
        }
        if ("stdio".equals(transportType) && server.command().isBlank()) {
            return failed(server, "command 为空");
        }
        if (("http".equals(transportType) || "streamable-http".equals(transportType) || "sse".equals(transportType)) && server.url().isBlank()) {
            return failed(server, transportType + " url 为空");
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
            return new ServerLoad(server, new ServerStatus(server.name(), true, listed.size(), ""), client, listed);
        } catch (Exception e) {
            if (client != null) client.closeGracefully();
            return failed(server, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static ServerLoad failed(McpConfig.Server server, String error) {
        return new ServerLoad(server, new ServerStatus(server.name(), false, 0, error), null, List.of());
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
