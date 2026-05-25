package com.coder.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigTest {
    @TempDir
    Path temp;

    @Test
    void missingConfigIsNotConfigured() {
        McpConfig config = McpConfig.load(temp.resolve("mcp.json"));

        assertFalse(config.configured());
        assertTrue(config.servers().isEmpty());
    }

    @Test
    void parsesStdioServerConfig() throws Exception {
        Path file = temp.resolve("mcp.json");
        Files.writeString(file, """
                {
                  "mcpServers": {
                    "filesystem": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
                      "env": {"A": "B"}
                    }
                  }
                }
                """);

        McpConfig config = McpConfig.load(file);

        assertTrue(config.configured());
        assertEquals(1, config.servers().size());
        McpConfig.Server server = config.servers().getFirst();
        assertEquals("filesystem", server.name());
        assertEquals("", server.type());
        assertEquals("npx", server.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), server.args());
        assertEquals(Map.of("A", "B"), server.env());
        assertEquals("", server.url());
        assertEquals("", server.endpoint());
        assertTrue(server.headers().isEmpty());
    }

    @Test
    void parsesHttpServerConfig() throws Exception {
        Path file = temp.resolve("mcp.json");
        Files.writeString(file, """
                {
                  "mcpServers": {
                    "remote": {
                      "type": "http",
                      "url": "http://localhost:3000",
                      "endpoint": "/mcp",
                      "headers": {"Authorization": "Bearer test"}
                    }
                  }
                }
                """);

        McpConfig.Server server = McpConfig.load(file).servers().getFirst();

        assertEquals("remote", server.name());
        assertEquals("http", server.type());
        assertEquals("http://localhost:3000", server.url());
        assertEquals("/mcp", server.endpoint());
        assertEquals(Map.of("Authorization", "Bearer test"), server.headers());
    }

    @Test
    void sanitizesAndDeduplicatesToolNames() {
        assertEquals("mcp_my_server_read_file", McpManager.toolName("my-server", "read file"));

        LinkedHashSet<String> used = new LinkedHashSet<>(List.of("mcp_a_b"));
        assertEquals("mcp_a_b_2", McpManager.unique("mcp_a_b", used));
    }

    @Test
    void reportsInvalidServerWithoutStartingAnything() {
        McpConfig config = new McpConfig(true, List.of(new McpConfig.Server("bad", "", List.of(), Map.of())));

        McpManager manager = McpManager.load(config, new LinkedHashSet<>());

        assertTrue(manager.summary().contains("bad: command 为空"));
        assertTrue(manager.details().contains("command 为空"));
    }

    @Test
    void reportsInvalidHttpServerWithoutStartingAnything() {
        McpConfig.Server server = new McpConfig.Server("bad-http", "http", "", List.of(), Map.of(), "", "", Map.of());

        McpManager manager = McpManager.load(new McpConfig(true, List.of(server)), new LinkedHashSet<>());

        assertTrue(manager.summary().contains("bad-http: http url 为空"));
        assertTrue(manager.details().contains("http url 为空"));
    }

    @Test
    void reportsUnsupportedTypeWithoutStartingAnything() {
        McpConfig.Server server = new McpConfig.Server("bad-type", "websocket", "", List.of(), Map.of(), "ws://localhost", "", Map.of());

        McpManager manager = McpManager.load(new McpConfig(true, List.of(server)), new LinkedHashSet<>());

        assertTrue(manager.summary().contains("bad-type: 不支持的 MCP type: websocket"));
        assertTrue(manager.details().contains("不支持的 MCP type: websocket"));
    }
}
