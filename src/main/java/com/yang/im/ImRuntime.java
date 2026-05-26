package com.yang.im;

/** 封装 IM 当前 chat id 和 IM 入口调用。 */
public final class ImRuntime {
    @FunctionalInterface
    public interface ChatHandler {
        String chat(String input) throws Exception;
    }

    private final ImClient im;
    private final ImContext context = new ImContext();

    public ImRuntime(ImClient im) {
        this.im = im;
    }

    public String chat(String chatId, String userInput, ChatHandler handler) throws Exception {
        return context.chat(chatId, userInput, handler::chat);
    }

    public ImClient imClient() {
        return im;
    }

    public String currentChatId() {
        return context.currentChatId();
    }

    public void setCurrentChatId(String chatId) {
        context.setCurrentChatId(chatId);
    }
}
