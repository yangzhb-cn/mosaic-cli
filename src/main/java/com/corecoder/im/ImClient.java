package com.corecoder.im;

import java.util.function.Consumer;

public interface ImClient {
    void start(Consumer<ImMessage> handler);

    void stop();

    void send(String chatId, String text) throws Exception;

    void typing(String chatId);
}
