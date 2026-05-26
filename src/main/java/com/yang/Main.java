package com.yang;

import com.yang.cli.CliBanner;
import com.yang.cli.CliCommands;
import com.yang.agent.Agent;
import com.yang.config.Config;
import com.yang.im.ImClient;
import com.yang.im.ImMessage;
import com.yang.im.TelegramImClient;
import com.yang.llm.LlmClient;
import com.yang.mcp.McpManager;
import com.yang.session.SessionStore;
import com.yang.skill.Skill;
import com.yang.skill.SkillLoader;
import com.yang.tool.Tools;

import java.util.List;
import java.util.Set;

/** 程序入口，负责装配配置、LLM、MCP、Skill、IM 和 CLI 主循环。 */
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

        Set<String> usedToolNames = Tools.names(Tools.all(null));
        if (im != null) usedToolNames.add("send_message");
        McpManager mcp = McpManager.loadDefault(usedToolNames);
        List<Skill> skills = SkillLoader.loadDefault();

        System.out.println(mcp.summary());
        System.out.println("Skills: " + skills.size() + " loaded");

        // 传入最大窗口，便于上下文压缩的配置策略
        Agent agent = new Agent(llm, c.maxContextTokens, im, mcp.tools(), skills);
        // 创建会话存储
        SessionStore sessions = new SessionStore();

        // 启动 Telegram 后台监听
        if (im != null) startTelegram(im, agent, c);
        
        // 启动命令行交互
        try {
            CliCommands.repl(agent, llm, sessions, mcp);
        } finally {
            mcp.close();
        }
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

}
