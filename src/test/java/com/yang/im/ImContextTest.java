package com.yang.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImContextTest {
    @Test
    void tracksCurrentChatIdWhileDelegatingChat() throws Exception {
        ImContext context = new ImContext();

        String response = context.chat("chat-1", "hello", input -> "reply: " + input);

        assertEquals("reply: hello", response);
        assertEquals("chat-1", context.currentChatId());
    }
}
