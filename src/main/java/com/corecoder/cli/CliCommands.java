package com.corecoder.cli;


import com.corecoder.Agent;
import com.corecoder.ContextManager;
import com.corecoder.LlmClient;
import com.corecoder.SessionStore;
import com.corecoder.tools.Tools;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.LineReader.Option;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.util.List;
import java.util.Map;

// 斜杠命令
public final class CliCommands {
    private static final List<String> COMMANDS = List.of(
            "/reset",
            "/tokens",
            "/compact",
            "/diff",
            "/save",
            "/sessions",
            "/exit"
    );

    private CliCommands() {
    }

    public static void repl(Agent agent, LlmClient llm, SessionStore sessions) throws Exception {
        System.out.println("💡 输入 / 后按 Tab 查看命令，输入 /exit 退出。");
        Terminal terminal = terminal();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(COMMANDS))
                .option(Option.AUTO_LIST, true)
                .build();
        while (true) {
            String line;
            try {
                line = reader.readLine("👤 你 > ").strip();
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
            if (handle(line, agent, llm, sessions)) {
                continue;
            }
            chat(agent, line);
        }
    }

    private static Terminal terminal() throws Exception {
        boolean interactive = System.console() != null;
        return TerminalBuilder.builder()
                .system(interactive)
                .dumb(!interactive)
                .streams(System.in, System.out)
                .build();
    }

    public static boolean handle(String line, Agent agent, LlmClient llm, SessionStore sessions) throws Exception {
        if (line.equals("/reset")) {
            agent.reset();
            System.out.println("🧹 对话已清空。");
            return true;
        }
        if (line.equals("/tokens")) {
            String s = "📊 Token: 输入=" + llm.totalPromptTokens + "，输出=" + llm.totalCompletionTokens + "，合计=" + (llm.totalPromptTokens + llm.totalCompletionTokens);
            System.out.println(s);
            return true;
        }
        if (line.equals("/compact")) {
            int before = ContextManager.estimateTokens(agent.messages);
            boolean changed = agent.context.maybeCompress(agent.messages, llm);
            int after = ContextManager.estimateTokens(agent.messages);
            System.out.println(changed ? "🗜️ 已压缩: " + before + " -> " + after + " tokens" : "✅ 无需压缩 (" + before + " tokens)");
            return true;
        }
        if (line.equals("/diff")) {
            if (Tools.changedFiles().isEmpty()) System.out.println("📄 本会话没有修改文件。");
            else Tools.changedFiles().stream().sorted().forEach(f -> System.out.println("  📝 " + f));
            return true;
        }
        if (line.equals("/save")) {
            String id = sessions.save(agent.messages, llm.model, null);
            System.out.println("💾 会话已保存: " + id);
            return true;
        }
        if (line.equals("/sessions")) {
            for (SessionStore.SessionInfo s : sessions.list()) System.out.println("  🗂️ " + s.id() + " (" + s.model() + ", " + s.savedAt() + ") " + s.preview());
            return true;
        }
        return false;
    }

    private static void chat(Agent agent, String line) throws Exception {
        System.out.println("🤔 思考中...");
        StringBuilder streamed = new StringBuilder();
        String response = agent.chat(line, tok -> {
            if (streamed.isEmpty()) {
                System.out.print("\n🤖 Agent: ");
            }
            streamed.append(tok);
            System.out.print(tok);
        }, (name, args) -> System.out.println("\n🔧 " + name + "(" + brief(args) + ")"));
        System.out.println(streamed.isEmpty() ? "🤖 Agent: " + response : "");
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
