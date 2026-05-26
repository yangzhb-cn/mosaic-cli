package com.yang.cli;

import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.LineReader.Option;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import com.yang.Agent;
import com.yang.context.ContextManager;
import com.yang.LlmClient;
import com.yang.session.SessionStore;
import com.yang.mcp.McpManager;
import com.yang.tool.Tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* 
 * CLI 命令集合
 */
public final class CliCommands {
    private static final List<Candidate> COMMANDS = List.of(
            command("/reset", "清空当前对话"),
            command("/tokens", "查看 token 使用情况"),
            command("/compact", "压缩上下文"),
            command("/diff", "查看当前会话修改了哪些文件"),
            command("/mcp", "查看 MCP 加载状态"),
            command("/last-request", "查看上一轮发给 LLM 的完整 JSON 请求"),
            command("/audit", "查看当前对话工具调用统计"),
            command("/audit save", "保存当前对话工具调用统计"),
            command("/save", "保存当前会话"),
            command("/load", "加载保存的会话"),
            command("/session", "查看当前会话用户消息"),
            command("/session list", "列出保存的会话"),
            command("/exit", "退出"));

    private CliCommands() {
    }

    private static Candidate command(String name, String description) {
        return new Candidate(name, name, null, description, null, null, true);
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
                .completer(new StringsCompleter(COMMANDS))
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
            if (handle(line, agent, llm, sessions, mcp)) {
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

    // 负责处理斜杠命令
    public static boolean handle(String line, Agent agent, LlmClient llm, SessionStore sessions) throws Exception {
        return handle(line, agent, llm, sessions, McpManager.empty());
    }

    public static boolean handle(String line, Agent agent, LlmClient llm, SessionStore sessions, McpManager mcp) throws Exception {
        if (line.equals("/reset")) {
            agent.reset();
            System.out.println("🧹 对话已清空。");
            return true;
        }
        if (line.equals("/tokens")) {
            String s = "📊 Token: 总输入=" + llm.totalPromptTokens + "，缓存输入=" + llm.totalCachedPromptTokens
                    + "，输出=" + llm.totalCompletionTokens + "，合计="
                    + (llm.totalPromptTokens + llm.totalCompletionTokens)
                    + "，上下文=" + agent.lastTokenUsage().contextPercent() + "%";
            System.out.println(s);
            return true;
        }
        if (line.equals("/compact")) {
            // 压缩前估算 token 数
            int before = ContextManager.estimateTokens(agent.messages);
            // 尝试压缩消息上下文
            boolean changed = agent.context.maybeCompress(agent.messages, llm);
            // 压缩后的 token 数
            int after = ContextManager.estimateTokens(agent.messages);
            System.out.println(
                    changed ? "🗜️ 已压缩: " + before + " -> " + after + " tokens" : "✅ 无需压缩 (" + before + " tokens)");
            return true;
        }
        if (line.equals("/diff")) {
            // 调用 Tools.changedFiles() 获取当前会话修改过的文件列表
            if (Tools.changedFiles().isEmpty())
                System.out.println("📄 本会话没有修改文件。");
            else
                Tools.changedFiles().stream().sorted().forEach(f -> System.out.println("  📝 " + f));
            return true;
        }
        if (line.equals("/mcp")) {
            System.out.println(mcp.details());
            return true;
        }
        if (line.equals("/last-request")) {
            String json = llm == null ? "" : llm.lastRequestJson();
            System.out.println(json == null || json.isBlank() ? "📭 暂无 LLM 请求。" : json);
            return true;
        }
        if (line.equals("/audit")) {
            System.out.println(agent.audit().table());
            return true;
        }
        if (line.equals("/audit save")) {
            Path path = agent.audit().save();
            System.out.println("💾 审计统计已保存: " + path);
            return true;
        }
        if (line.equals("/save")) {
            String id = sessions.save(agent.messages, llm.model, null, agent.conversationId());
            System.out.println("💾 会话已保存: " + id);
            return true;
        }
        if (line.equals("/load")) {
            System.out.println("用法: /load <session_id>");
            return true;
        }
        if (line.startsWith("/load ")) {
            String id = line.substring("/load ".length()).strip();
            SessionStore.Session session = sessions.load(id);
            if (session == null) {
                System.out.println("📭 未找到会话: " + id);
                return true;
            }
            agent.loadSession(session.messages(), session.conversationId());
            System.out.println("📂 会话已加载: " + id);
            if (llm != null && session.model() != null && !session.model().equals(llm.model)) {
                System.out.println("⚠️ 保存时模型是 " + session.model() + "，当前模型是 " + llm.model + "。");
            }
            return true;
        }
        if (line.equals("/session list")) {
            printSessionList(sessions);
            return true;
        }
        if (line.equals("/session")) {
            printUserMessages(agent.messages);
            return true;
        }
        if (line.startsWith("/session ")) {
            String id = line.substring("/session ".length()).strip();
            SessionStore.Session session = sessions.load(id);
            if (session == null) {
                System.out.println("📭 未找到会话: " + id);
                return true;
            }
            printUserMessages(session.messages());
            return true;
        }
        return false;
    }

    private static void printSessionList(SessionStore sessions) throws Exception {
        for (SessionStore.SessionInfo s : sessions.list())
            System.out.println("  🗂️ " + s.id() + " (" + s.model() + ", " + s.savedAt() + ") " + s.preview());
    }

    private static void printUserMessages(List<Map<String, Object>> messages) {
        int i = 1;
        for (Map<String, Object> message : messages) {
            if (!"user".equals(message.get("role"))) continue;
            String content = ContextManager.stripSystemReminder(String.valueOf(message.getOrDefault("content", ""))).strip();
            if (content.isBlank()) continue;
            System.out.println(i + ". " + content);
            i++;
        }
        if (i == 1) System.out.println("📭 当前会话没有用户消息。");
    }

    // 处理普通聊天输入。
    private static void chat(Agent agent, String line) throws Exception {
        System.out.println("\n🤔 思考中...");
        // 累积模型流式输出的内容
        StringBuilder streamed = new StringBuilder();
        long started = System.nanoTime();

        // agent.chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool)
        // 真正发起一次聊天（涉及两次回调）
        String response = agent.chat(line, tok -> {
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
