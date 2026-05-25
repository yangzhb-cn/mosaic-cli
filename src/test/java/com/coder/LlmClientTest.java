package com.coder;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest {
    HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesStreamingTextAndUsage() throws Exception {
        String body = """
                data: {"choices":[{"delta":{"content":"hello "}}]}

                data: {"choices":[{"delta":{"content":"world"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

                data: [DONE]

                """;
        String url = startServer(body);
        LlmClient llm = new LlmClient("test-model", "key", url, 0);
        List<String> tokens = new ArrayList<>();

        LlmClient.Response r = llm.chat(List.of(Map.of("role", "user", "content", "hi")), null, tokens::add, null);

        assertEquals("hello world", r.content());
        assertEquals(List.of("hello ", "world"), tokens);
        assertEquals(10, r.promptTokens());
        assertEquals(5, r.completionTokens());
        assertEquals(10, llm.totalPromptTokens);
        assertEquals(5, llm.totalCompletionTokens);
    }

    @Test
    void parsesChunkedToolCallArguments() throws Exception {
        String body = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"Read","arguments":"{\\\"file_"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"path\\\":\\\"a.txt\\\"}"}}]}}]}

                data: [DONE]

                """;
        String url = startServer(body);
        LlmClient llm = new LlmClient("test-model", "key", url, 0);

        LlmClient.Response r = llm.chat(List.of(Map.of("role", "user", "content", "hi")), List.of(), null, null);

        assertEquals(1, r.toolCalls().size());
        assertEquals("call_1", r.toolCalls().getFirst().id());
        assertEquals("Read", r.toolCalls().getFirst().name());
        assertEquals("a.txt", r.toolCalls().getFirst().arguments().get("file_path"));
    }

    @Test
    void notifiesWhenStreamingToolArgumentsAreComplete() throws Exception {
        // 第一段只给出部分 arguments，此时还不能触发工具就绪。
        String body = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"Read","arguments":"{\\\"file_"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"path\\\":\\\"a.txt\\\"}"}}]}}]}

                data: [DONE]

                """;
        // 启动一个本地 SSE 假服务。
        String url = startServer(body);
        // 构造测试 LLM 客户端。
        LlmClient llm = new LlmClient("test-model", "key", url, 0);
        // 记录 tool-ready 回调收到的工具信息。
        List<String> ready = new ArrayList<>();

        // 第二段 arguments 拼完后，应立即触发 tool-ready 回调。
        LlmClient.Response r = llm.chat(List.of(Map.of("role", "user", "content", "hi")), List.of(), null,
                (idx, tc) -> ready.add(idx + ":" + tc.name() + ":" + tc.arguments().get("file_path")));

        // 回调只触发一次，并携带完整参数。
        assertEquals(List.of("0:Read:a.txt"), ready);
        // 最终 Response 仍然保留完整 tool_calls。
        assertEquals(1, r.toolCalls().size());
        assertEquals("call_1", r.toolCalls().getFirst().id());
        assertEquals("Read", r.toolCalls().getFirst().name());
        assertEquals("a.txt", r.toolCalls().getFirst().arguments().get("file_path"));
    }

    @Test
    void preservesReasoningContentInAssistantMessage() throws Exception {
        String body = """
                data: {"choices":[{"delta":{"reasoning_content":"思考"}}]}

                data: {"choices":[{"delta":{"content":"回答"}}]}

                data: [DONE]

                """;
        String url = startServer(body);
        LlmClient llm = new LlmClient("test-model", "key", url, 0);

        LlmClient.Response r = llm.chat(List.of(Map.of("role", "user", "content", "hi")), null, null, null);

        assertEquals("回答", r.content());
        assertEquals("思考", r.reasoningContent());
        assertEquals("思考", r.message().get("reasoning_content"));
    }

    private String startServer(String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }
}
