package com.corecoder;


import com.corecoder.tools.Tools;

import java.util.Map;
import java.util.Scanner;

// 斜杠命令
public final class CliCommands {
    private static final String HELP = """
            命令:
              /help          显示帮助
              /reset         清空对话历史
              /tokens        显示 token 使用量
              /compact       压缩对话上下文
              /diff          显示本会话修改过的文件
              /save          保存会话
              /sessions      列出已保存会话
              /exit           退出
            """;

    private CliCommands() {
    }

    public static void repl(Agent agent, LlmClient llm, SessionStore sessions) throws Exception {
        System.out.println("CoreCoder v" + Main.VERSION + "  模型: " + llm.model);
        System.out.println("输入 /help 查看命令，输入 quit 退出。");
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("你 > ");
            if (!in.hasNextLine()) break;
            String line = in.nextLine().strip();
            if (line.isEmpty()) continue;
            if (isExit(line)) break;
            if (handle(line, agent, llm, sessions)) continue;
            chat(agent, line);
        }
    }

    public static boolean handle(String line, Agent agent, LlmClient llm, SessionStore sessions) throws Exception {
        if (line.equals("/help")) {
            System.out.println(HELP);
            return true;
        }
        if (line.equals("/reset")) {
            agent.reset();
            System.out.println("对话已清空。");
            return true;
        }
        if (line.equals("/tokens")) {
            String s = "tokens: " + llm.totalPromptTokens + " 输入 + " + llm.totalCompletionTokens + " 输出 = " + (llm.totalPromptTokens + llm.totalCompletionTokens);
            System.out.println(s);
            return true;
        }
        if (line.equals("/compact")) {
            int before = ContextManager.estimateTokens(agent.messages);
            boolean changed = agent.context.maybeCompress(agent.messages, llm);
            int after = ContextManager.estimateTokens(agent.messages);
            System.out.println(changed ? "已压缩: " + before + " -> " + after + " tokens" : "无需压缩 (" + before + " tokens)");
            return true;
        }
        if (line.equals("/diff")) {
            if (Tools.changedFiles().isEmpty()) System.out.println("本会话没有修改文件。");
            else Tools.changedFiles().stream().sorted().forEach(f -> System.out.println("  " + f));
            return true;
        }
        if (line.equals("/save")) {
            String id = sessions.save(agent.messages, llm.model, null);
            System.out.println("会话已保存: " + id);
            return true;
        }
        if (line.equals("/sessions")) {
            for (SessionStore.SessionInfo s : sessions.list()) System.out.println("  " + s.id() + " (" + s.model() + ", " + s.savedAt() + ") " + s.preview());
            return true;
        }
        return false;
    }

    private static void chat(Agent agent, String line) throws Exception {
        StringBuilder streamed = new StringBuilder();
        String response = agent.chat(line, tok -> {
            streamed.append(tok);
            System.out.print(tok);
        }, (name, args) -> System.out.println("\n> " + name + "(" + brief(args) + ")"));
        System.out.println(streamed.isEmpty() ? response : "");
    }

    private static boolean isExit(String line) {
        return line.equals("/exit");
    }

    private static String brief(Map<String, Object> args) {
        String s = args.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
