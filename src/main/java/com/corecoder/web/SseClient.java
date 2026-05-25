package com.corecoder.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class SseClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutputStream out;
    private final AtomicBoolean open = new AtomicBoolean(true);

    SseClient(OutputStream out) {
        this.out = out;
    }

    boolean isOpen() {
        return open.get();
    }

    void send(String event, Object data) {
        if (!open.get()) return;
        try {
            String payload = "event: " + event + "\n"
                    + "data: " + JSON.writeValueAsString(data) + "\n\n";
            synchronized (out) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            close();
        }
    }

    void close() {
        if (!open.getAndSet(false)) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
