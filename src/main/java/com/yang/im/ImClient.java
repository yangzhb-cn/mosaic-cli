package com.yang.im;

import java.util.function.Consumer;

/** 定义外部 IM 通道需要实现的收发消息接口。 */
public interface ImClient {
    void start(Consumer<ImMessage> handler);

    void stop();

    void send(String chatId, String text) throws Exception;

    void typing(String chatId);
}
