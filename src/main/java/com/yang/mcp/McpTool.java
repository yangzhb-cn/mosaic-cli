package com.yang.mcp;

import com.yang.tool.Tools;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把单个 MCP 工具包装成 Tools.Tool 接口。 */
final class McpTool implements Tools.Tool {
    private final String name;
    private final String server;
    private final McpSyncClient client;
    private final io.modelcontextprotocol.spec.McpSchema.Tool tool;

    McpTool(String name, String server, McpSyncClient client, io.modelcontextprotocol.spec.McpSchema.Tool tool) {
        this.name = name;
        this.server = server;
        this.client = client;
        this.tool = tool;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        String text = tool.description() == null || tool.description().isBlank() ? tool.name() : tool.description();
        return "MCP tool from " + server + ": " + text;
    }

    @Override
    public Map<String, Object> parameters() {
        JsonSchema schema = tool.inputSchema();
        if (schema == null) return Map.of("type", "object", "properties", Map.of(), "additionalProperties", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", schema.type() == null ? "object" : schema.type());
        out.put("properties", schema.properties() == null ? Map.of() : schema.properties());
        out.put("required", schema.required() == null ? List.of() : schema.required());
        if (schema.additionalProperties() != null) out.put("additionalProperties", schema.additionalProperties());
        if (schema.defs() != null) out.put("$defs", schema.defs());
        if (schema.definitions() != null) out.put("definitions", schema.definitions());
        return McpSchemaSimplifier.simplify(out);
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            CallToolResult result;
            synchronized (client) {
                result = client.callTool(new CallToolRequest(tool.name(), args));
            }
            String text = format(result);
            return Boolean.TRUE.equals(result.isError()) ? "错误: " + text : text;
        } catch (Exception e) {
            return "错误: MCP tool " + name + " 失败: " + e.getMessage();
        }
    }

    private String format(CallToolResult result) {
        StringBuilder out = new StringBuilder();
        if (result.content() != null) {
            for (Content content : result.content()) {
                if (content instanceof TextContent text) {
                    out.append(text.text()).append('\n');
                } else {
                    out.append("[").append(content.type()).append("] ").append(content).append('\n');
                }
            }
        }
        if (result.structuredContent() != null) out.append(result.structuredContent()).append('\n');
        String text = out.toString().stripTrailing();
        return text.isBlank() ? "(empty MCP result)" : text;
    }
}
