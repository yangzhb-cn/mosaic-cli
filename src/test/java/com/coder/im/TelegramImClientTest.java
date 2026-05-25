package com.coder.im;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramImClientTest {
    @Test
    void splitsLongMessagesForTelegram() {
        List<String> chunks = TelegramImClient.splitMessage("a".repeat(4097));
        assertEquals(2, chunks.size());
        assertEquals(4096, chunks.get(0).length());
        assertEquals(1, chunks.get(1).length());
    }

    @Test
    void skipsBlankMessages() {
        assertTrue(TelegramImClient.splitMessage(" ").isEmpty());
    }
}
