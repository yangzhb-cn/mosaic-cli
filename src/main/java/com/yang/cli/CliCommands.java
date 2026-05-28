package com.yang.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.LineReader.Option;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import com.yang.agent.Agent;
import com.yang.llm.LlmClient;
import com.yang.session.SessionManager;
import com.yang.mcp.McpManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 管理交互式 REPL、输入补全、普通聊天流式输出和 token 用量展示。 */
public final class CliCommands {
    private CliCommands() {
    }

    // 交互循环
    public static void repl(Agent agent, LlmClient llm, SessionManager sessions) throws Exception {
        repl(agent, llm, sessions, McpManager.empty());
    }

    public static void repl(Agent agent, LlmClient llm, SessionManager sessions, McpManager mcp) throws Exception {
        System.out.println("💡 输入 / 后按 Tab 查看命令，输入 /exit 退出。");

        // 创建终端对象，构建输入读取器，绑定终端，设置命令补全，自动列出匹配的补全项，生成LineReader
        Terminal terminal = terminal();
        LineReader reader = buildReader(terminal, sessions);

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

    static LineReader buildReader(Terminal terminal, SessionManager sessions) throws IOException {
        Path dataDir = sessions == null ? Path.of("").toAbsolutePath().resolve("data") : sessions.dataDir();
        Files.createDirectories(dataDir);
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new CliCommandCompleter(sessions))
                .variable(LineReader.HISTORY_FILE, dataDir.resolve("cli-history"))
                .variable(LineReader.HISTORY_SIZE, 1000)
                .option(Option.AUTO_LIST, true)
                .option(Option.HISTORY_IGNORE_DUPS, true)
                .option(Option.HISTORY_IGNORE_SPACE, true)
                .build();
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
        CliStatusPrinter status = new CliStatusPrinter(System.out);
        status.chatStarted();
        long started = System.nanoTime();

        // agent.chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool)
        // 真正发起一次聊天（涉及两次回调）
        String response = agent.chatCli(line, status::assistantToken, status);

        // 如果已经流式了，就打印空行，否则 response 兜底。多行内容单独换行，避免表格被前缀挤歪。
        status.chatFinished(response, agent.lastTokenUsage(), System.nanoTime() - started);
    }

    private static boolean isExit(String line) {
        return line.equals("/exit");
    }

}
