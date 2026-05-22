package com.corecoder;

import com.corecoder.cli.CliBanner;
import com.corecoder.cli.CliCommands;

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

        LlmClient llm = new LlmClient(c.model, c.apiKey, c.baseUrl, c.temperature);
        // 传入最大窗口，便于上下文压缩的配置策略
        Agent agent = new Agent(llm, c.maxContextTokens);
        SessionStore sessions = new SessionStore();

        CliCommands.repl(agent, llm, sessions);
    }
}
