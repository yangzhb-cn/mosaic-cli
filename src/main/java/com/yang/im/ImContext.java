package com.yang.im;

/** 保存当前 IM 会话上下文，避免发送消息工具丢失目标 chatId。 */
public final class ImContext {
    /** IM 消息进入 Agent 的回调接口。 */
    @FunctionalInterface
    public interface ImChatHandler {
        String chat(String userInput) throws Exception;
    }

    private volatile String currentChatId;

    public String chat(String chatId, String userInput, ImChatHandler handler) throws Exception {
        currentChatId = chatId;
        return handler.chat(userInput);
    }

    public String currentChatId() {
        return currentChatId;
    }

    public void setCurrentChatId(String chatId) {
        this.currentChatId = chatId;
    }
}
