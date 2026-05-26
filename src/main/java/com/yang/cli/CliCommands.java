package com.yang.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.LineReader.Option;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import com.yang.agent.Agent;
import com.yang.llm.LlmClient;
import com.yang.session.SessionStore;
import com.yang.mcp.McpManager;

import java.util.Locale;
import java.util.Map;

/** 管理交互式 REPL、输入补全、普通聊天流式输出和 token 用量展示。 */
public final class CliCommands {
    private CliCommands() {
    }

    // 交互循环
    public static void repl(Agent agent, LlmClient llm, SessionStore sessions) throws Exception {
        repl(agent, llm, sessions, McpManager.empty());
    }

    public static void repl(Agent agent, LlmClient llm, SessionStore sessions, McpManager mcp) throws Exception {
        System.out.println("💡 输入 / 后按 Tab 查看命令，输入 /exit 退出。");

        // 创建终端对象，构建输入读取器，绑定终端，设置命令补全，自动列出匹配的补全项，生成LineReader
        Terminal terminal = terminal();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(CliRouter.commands()))
                .option(Option.AUTO_LIST, true)
                .build();

        while (true) {
            String line;
            try {
                line = reader.readLine("\n👤 你 > ").strip();
            } catch (UserInterruptException ignored) {
                continue;
            } catch (EndOfFileException ignored) {
                break;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (isExit(line)) {
                break;
            }
            // 把输入当作斜杠命令处理
            // 如果返回 true，说明它已经处理了这个命令，不需要再当聊天内容处理
            if (CliRouter.handle(line, agent, llm, sessions, mcp)) {
                continue;
            }
            // 当成普通聊天内容交给 chat(...) 处理
            chat(agent, line);
        }
    }

    private static Terminal terminal() throws Exception {
        boolean interactive = System.console() != null;

        return TerminalBuilder.builder()
                // 如果是交互式终端，就使用系统终端
                .system(interactive)
                // 如果不是交互式，就用简化模式
                .dumb(!interactive)
                // 绑定标准输入输出
                .streams(System.in, System.out)
                .build();
    }

    // 处理普通聊天输入。
    private static void chat(Agent agent, String line) throws Exception {
        System.out.println("\n🤔 思考中...");
        // 累积模型流式输出的内容
        StringBuilder streamed = new StringBuilder();
        long started = System.nanoTime();

        // agent.chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool)
        // 真正发起一次聊天（涉及两次回调）
        String response = agent.chatCli(line, tok -> {
            String out = streamed.isEmpty() ? stripLeadingBlank(tok) : tok;
            if (out.isEmpty()) {
                return;
            }
            // 如果这是第一个有效 token，先打印一次 🤖 Agent:
            if (streamed.isEmpty()) {
                System.out.print("\n🤖 Agent: ");
            }
            // 把当前有效 token 加到缓冲区里
            streamed.append(out);
            // 同时直接打印到终端，这就是“边生成边显示”的流式效果
            System.out.print(out);

            // agent调用了工具就回调，打印工具名和参数摘要
        }, (name, args) -> System.out.println("\n🔧 " + name + "(" + brief(args) + ")"));

        // 如果已经流式了，就打印空行，否则response兜底
        System.out.println(streamed.isEmpty() ? "🤖 Agent: " + stripLeadingBlank(response) : "");
        printUsage(agent.lastTokenUsage(), System.nanoTime() - started);
    }

    private static void printUsage(Agent.TokenUsage usage, long elapsedNanos) {
        System.out.println("\n📊 Token: 总输入=" + usage.promptTokens()
                + "，缓存输入=" + usage.cachedPromptTokens()
                + "，输出=" + usage.completionTokens()
                + "，耗时=" + duration(elapsedNanos)
                + "，上下文=" + usage.contextPercent() + "% used");
    }

    private static String duration(long elapsedNanos) {
        double seconds = Math.max(0.001, elapsedNanos / 1_000_000_000d);
        if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", seconds);
        long minutes = (long) (seconds / 60);
        return minutes + "m " + String.format(Locale.ROOT, "%.1fs", seconds % 60);
    }

    private static boolean isExit(String line) {
        return line.equals("/exit");
    }

    // 丢弃模型回复开头的空格、制表符和空行，避免终端前缀后先换行。
    private static String stripLeadingBlank(String text) {
        return text == null ? "" : text.replaceFirst("^[\\s\\u3000]+", "");
    }

    // 用来把工具参数压缩成一个简短字符串，方便打印
    private static String brief(Map<String, Object> args) {
        String s = args.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
