package com.yang.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Telegram Bot 长轮询客户端，把 Telegram 消息适配成统一 IM 事件。 */
public class TelegramImClient implements ImClient {
    private static final String BASE_URL = "https://api.telegram.org/bot";
    private static final int MAX_MESSAGE = 4096;
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String token;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http;
    private volatile boolean running = true;
    private long lastUpdateId;

    public TelegramImClient(String token) {
        this(token, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build());
    }

    TelegramImClient(String token, OkHttpClient http) {
        this.token = token;
        this.http = http;
    }

    @Override
    public void start(Consumer<ImMessage> handler) {
        while (running) {
            try {
                for (Update update : updates(lastUpdateId + 1, 30)) {
                    lastUpdateId = update.id();
                    if (update.message() != null) handler.accept(update.message());
                }
            } catch (Exception e) {
                System.err.println("Telegram 轮询失败: " + e.getMessage());
                sleep();
            }
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void send(String chatId, String text) throws Exception {
        for (String chunk : splitMessage(text)) sendOne(chatId, chunk);
    }

    @Override
    public void typing(String chatId) {
        try {
            post("sendChatAction", Map.of("chat_id", chatId, "action", "typing"));
        } catch (Exception ignored) {
        }
    }

    static List<String> splitMessage(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += MAX_MESSAGE) {
            chunks.add(text.substring(i, Math.min(i + MAX_MESSAGE, text.length())));
        }
        return chunks;
    }

    private List<Update> updates(long offset, int timeout) throws IOException {
        JsonNode root = post("getUpdates", Map.of("offset", offset, "timeout", timeout));
        List<Update> out = new ArrayList<>();
        for (JsonNode node : root.path("result")) {
            long id = node.path("update_id").asLong();
            JsonNode msg = node.path("message");
            String text = msg.path("text").asText(null);
            String chatId = msg.path("chat").path("id").asText(null);
            String userId = msg.path("from").path("id").asText(null);
            if (text != null && chatId != null && userId != null) out.add(new Update(id, new ImMessage(chatId, userId, text)));
            else out.add(new Update(id, null));
        }
        return out;
    }

    private void sendOne(String chatId, String text) throws IOException {
        if (text == null || text.isBlank()) return;
        post("sendMessage", Map.of("chat_id", chatId, "text", text));
    }

    private JsonNode post(String method, Map<String, Object> body) throws IOException {
        Request req = new Request.Builder()
                .url(BASE_URL + token + "/" + method)
                .post(RequestBody.create(json.writeValueAsString(body), JSON_TYPE))
                .build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException(method + " HTTP " + res.code());
            String text = res.body() == null ? "" : res.body().string();
            JsonNode root = json.readTree(text);
            if (!root.path("ok").asBoolean(false)) throw new IOException(method + " 返回失败: " + text);
            return root;
        }
    }

    private void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        }
    }

    /** Telegram update_id 与解析后的 IM 消息。 */
    private record Update(long id, ImMessage message) {
    }
}
