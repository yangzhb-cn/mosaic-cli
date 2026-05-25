package com.corecoder;

import com.corecoder.cli.CliBanner;
import com.corecoder.cli.CliCommands;
import com.corecoder.im.ImClient;
import com.corecoder.im.ImMessage;
import com.corecoder.im.TelegramImClient;

public class Main {
    public static final String VERSION = "0.1.0";

    public static void main(String[] args) throws Exception {

        // 从.env 文件或者环境变量中获取llm设置：环境变量 > .env
        Config c = Config.fromEnv();
        if (c.apiKey == null || c.apiKey.isBlank()) {
            System.err.println("未找到 API key。请设置 DEEPSEEK_API_KEY。");
            System.exit(1);
        }

        // 打印banner
        CliBanner.print(Main.VERSION, c.model);

        ImClient im = c.telegramEnabled() ? new TelegramImClient(c.telegramBotToken) : null;
        LlmClient llm = new LlmClient(c.model, c.apiKey, c.baseUrl, c.temperature);
        // 传入最大窗口，便于上下文压缩的配置策略
        Agent agent = new Agent(llm, c.maxContextTokens, im);
        SessionStore sessions = new SessionStore();

        if (im != null) startTelegram(im, agent, c);
        CliCommands.repl(agent, llm, sessions);
    }

    private static void startTelegram(ImClient im, Agent agent, Config c) {
        Thread t = new Thread(() -> im.start(msg -> handleTelegram(im, agent, c, msg)), "telegram-im");
        t.setDaemon(true);
        t.start();
        System.out.println("📨 Telegram IM 已启动，CLI 仍可继续使用。");
    }

    private static void handleTelegram(ImClient im, Agent agent, Config c, ImMessage msg) {
        if (!c.telegramOwnerId.equals(msg.userId()) || msg.text() == null) return;
        try {
            if ("/reset".equals(msg.text().trim())) {
                agent.reset();
                im.send(msg.chatId(), "会话已重置。");
                return;
            }
            im.typing(msg.chatId());
            String response = agent.chatFromIm(msg.chatId(), msg.text());
            im.send(msg.chatId(), response);
        } catch (Exception e) {
            try {
                im.send(msg.chatId(), "错误: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }
}
