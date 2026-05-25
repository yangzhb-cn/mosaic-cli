package com.corecoder;

import com.corecoder.cli.CliBanner;
import com.corecoder.cli.CliCommands;
import com.corecoder.im.ImClient;
import com.corecoder.im.ImMessage;
import com.corecoder.im.TelegramImClient;
import com.corecoder.web.WebServer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class Main {
    // 版本号
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

        // 初始化 IM 客户端
        ImClient im = c.telegramEnabled() ? new TelegramImClient(c.telegramBotToken) : null;
        // 初始化 LLM 客户端
        LlmClient llm = new LlmClient(c.model, c.apiKey, c.baseUrl, c.temperature);

        // web模式：命令行参数里带了 --web，程序就进入 Web 模式
        if (hasArg(args, "--web")) {
            // 去找 --web 后面的参数作为端口,默认 8080
            int port = webPort(args);
            // config,llm,当前工作目录的绝对路径
            WebServer web = new WebServer(c, llm, Path.of("").toAbsolutePath());
            // 启动 WebServer
            web.start(port);
            new CountDownLatch(1).await();
            return;
        }

        // 传入最大窗口，便于上下文压缩的配置策略
        Agent agent = new Agent(llm, c.maxContextTokens, im);
        // 创建会话存储
        SessionStore sessions = new SessionStore();

        // 启动 Telegram 后台监听
        if (im != null) startTelegram(im, agent, c);
        
        // 启动命令行交互
        CliCommands.repl(agent, llm, sessions);
    }

    private static void startTelegram(ImClient im, Agent agent, Config c) {
        // 创建一个新线程,在线程里启动 Telegram 监听,收到消息后交给 handleTelegram(...) 处理
        Thread t = new Thread(() -> im.start(msg -> handleTelegram(im, agent, c, msg)), "telegram-im");
        // 设置为守护线程 setDaemon(true)，避免阻塞程序退出
        t.setDaemon(true);
        t.start();
        System.out.println("📨 Telegram IM 已启动，CLI 仍可继续使用。");
    }

    // Telegram 消息处理逻辑
    private static void handleTelegram(ImClient im, Agent agent, Config c, ImMessage msg) {
        //  发消息的人必须是配置中的 owner
        if (!c.telegramOwnerId.equals(msg.userId()) || msg.text() == null) return;
        try {
            // 处理 /reset
            if ("/reset".equals(msg.text().trim())) {
                agent.reset();
                im.send(msg.chatId(), "会话已重置。");
                return;
            }

            // 发送“正在输入”
            im.typing(msg.chatId());
            // 把 Telegram 消息内容交给 Agent 处理，然后把模型回复发回 Telegram
            String response = agent.chatFromIm(msg.chatId(), msg.text());
            im.send(msg.chatId(), response);
        } catch (Exception e) {
            try {
                im.send(msg.chatId(), "错误: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean hasArg(String[] args, String value) {
        for (String arg : args) {
            if (value.equals(arg)) return true;
        }
        return false;
    }

    // 去找 --web 后面的参数作为端口
    private static int webPort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--web".equals(args[i])) return Integer.parseInt(args[i + 1]);
        }
        return 8080;
    }
}
