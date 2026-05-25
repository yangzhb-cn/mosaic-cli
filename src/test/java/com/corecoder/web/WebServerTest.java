package com.corecoder.web;

import com.corecoder.Config;
import com.corecoder.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    WebServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void servesHomeFilesAndCreatesSession() throws Exception {
        Files.writeString(temp.resolve("note.txt"), "web");
        Config config = new Config();
        LlmClient llm = new LlmClient("test-model", "key", "http://localhost", 0);
        server = new WebServer(config, llm, temp);
        int port = server.start(0);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> home = client.send(request("GET", port, "/api/home", null), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, home.statusCode());
        assertEquals(temp.toString(), JSON.readTree(home.body()).get("cwd").asText());

        String encoded = URLEncoder.encode(temp.toString(), StandardCharsets.UTF_8);
        HttpResponse<String> files = client.send(request("GET", port, "/api/files/" + encoded + "?type=list", null), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, files.statusCode());
        assertTrue(files.body().contains("note.txt"));

        HttpResponse<String> created = client.send(request("POST", port, "/api/agent/new", "{\"cwd\":\"" + temp + "\"}"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        JsonNode session = JSON.readTree(created.body()).get("session");
        assertNotNull(session.get("id").asText());
        assertEquals(temp.toString(), session.get("cwd").asText());
    }

    private static HttpRequest request(String method, int port, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else {
            builder.GET();
        }
        return builder.build();
    }
}
