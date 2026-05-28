package com.yang.cli;

import com.yang.agent.Agent;
import com.yang.llm.LlmClient;
import com.yang.context.ContextManager;
import com.yang.mcp.McpManager;
import com.yang.session.SessionManager;
import com.yang.tool.Tools;
import org.jline.reader.Candidate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 路由斜杠命令到对应处理逻辑，保持 REPL 类只负责终端交互。 */
public final class CliRouter {
    private static final List<CommandSpec> COMMAND_SPECS = List.of(
            new CommandSpec("/reset", "Core", "清空当前对话"),
            new CommandSpec("/tokens", "Core", "查看 token 使用情况"),
            new CommandSpec("/compact", "Core", "压缩上下文"),
            new CommandSpec("/diff", "Core", "查看当前会话修改了哪些文件"),
            new CommandSpec("/exit", "Core", "退出"),
            new CommandSpec("/plan", "Plan", "进入计划生成模式"),
            new CommandSpec("/act", "Plan", "执行当前计划"),
            new CommandSpec("/cancel", "Plan", "取消当前计划"),
            new CommandSpec("/mcp", "System", "查看 MCP 加载状态"),
            new CommandSpec("/audit", "System", "查看当前对话工具调用统计"),
            new CommandSpec("/audit save", "System", "保存当前对话工具调用统计"),
            new CommandSpec("/memory update", "System", "提炼当前会话并更新长期记忆"),
            new CommandSpec("/session", "Session", "查看当前会话用户消息"),
            new CommandSpec("/session list", "Session", "列出所有会话"),
            new CommandSpec("/session new", "Session", "创建并切换到新会话"),
            new CommandSpec("/session switch", "Session", "切换到已有会话"));
    private static final List<Candidate> COMMANDS = COMMAND_SPECS.stream()
            .map(CliRouter::command)
            .toList();

    private CliRouter() {
    }

    public record CommandSpec(String command, String group, String description) {}

    public static List<CommandSpec> commandSpecs() {
        return COMMAND_SPECS;
    }

    public static List<Candidate> commands() {
        return COMMANDS;
    }

    private static Candidate command(CommandSpec spec) {
        return new Candidate(spec.command(), spec.command(), spec.group(), spec.description(), null, null, true);
    }

    public static boolean handle(String line, Agent agent, LlmClient llm, SessionManager sessions) throws Exception {
        return handle(line, agent, llm, sessions, McpManager.empty());
    }

    public static boolean handle(String line, Agent agent, LlmClient llm, SessionManager sessions, McpManager mcp) throws Exception {
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
            int before = ContextManager.estimateTokens(agent.messages);
            boolean changed = agent.context.maybeCompress(agent.messages, llm);
            int after = ContextManager.estimateTokens(agent.messages);
            if (changed && agent != null) agent.saveSession();
            System.out.println(changed ? "🗜️ 已压缩: " + before + " -> " + after + " tokens" : "✅ 无需压缩 (" + before + " tokens)");
            return true;
        }
        if (line.equals("/diff")) {
            if (Tools.changedFiles().isEmpty()) {
                System.out.println("📄 本会话没有修改文件。");
            } else {
                Tools.changedFiles().stream().sorted().forEach(f -> System.out.println("  📝 " + f));
            }
            return true;
        }
        if (line.equals("/mcp")) {
            System.out.println(mcp.details());
            return true;
        }
        if (line.equals("/plan")) {
            agent.enterPlanMode();
            System.out.println("🧭 请输入要规划的任务。");
            return true;
        }
        if (line.startsWith("/plan ")) {
            System.out.println("🧭 暂不支持 /plan <task>，请先输入 /plan，再输入要规划的任务。");
            return true;
        }
        if (line.equals("/act")) {
            CliStatusPrinter status = new CliStatusPrinter(System.out);
            System.out.println(agent.actPlan(status::planProgress));
            return true;
        }
        if (line.equals("/cancel")) {
            System.out.println(agent.cancelPlan());
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
        if (line.equals("/session list")) {
            printSessionList(sessions);
            return true;
        }
        if (line.equals("/session new") || line.startsWith("/session new ")) {
            String id = line.length() == "/session new".length() ? null : line.substring("/session new ".length()).strip();
            try {
                SessionManager.Session session = sessions.create(id, llm.model);
                agent.loadSession(session.messages(), session.conversationId(), session.auditRecords());
                System.out.println("🆕 已创建并切换到会话: " + session.id());
            } catch (IllegalArgumentException e) {
                System.out.println("❌ 错误: " + e.getMessage());
            }
            return true;
        }
        if (line.equals("/session switch")) {
            System.out.println("💡 用法: /session switch <session_id>");
            return true;
        }
        if (line.startsWith("/session switch ")) {
            String id = line.substring("/session switch ".length()).strip();
            SessionManager.Session session = sessions.switchTo(id);
            if (session == null) {
                System.out.println("📭 未找到会话: " + id);
                return true;
            }
            agent.loadSession(session.messages(), session.conversationId(), session.auditRecords());
            System.out.println("📂 已切换到会话: " + session.id());
            if (llm != null && session.model() != null && !session.model().equals(llm.model)) {
                System.out.println("⚠️ 保存时模型是 " + session.model() + "，当前模型是 " + llm.model + "。");
            }
            return true;
        }
        if (line.equals("/session")) {
            String id = agent == null ? "" : agent.sessionId();
            if (id != null && !id.isBlank()) System.out.println("🗂️ Session: " + id);
            printUserMessages(agent.messages);
            return true;
        }
        if (line.startsWith("/session ")) {
            System.out.println("💡 用法: /session、/session list、/session new [id]、/session switch <id>");
            return true;
        }
        if (line.equals("/memory update")) {
            System.out.println(agent.updateMemory());
            return true;
        }
        return false;
    }

    private static void printSessionList(SessionManager sessions) throws Exception {
        List<SessionManager.SessionInfo> all = sessions.list();
        if (all.isEmpty()) {
            System.out.println("📭 暂无会话。");
            return;
        }
        for (SessionManager.SessionInfo s : all) {
            String marker = s.active() ? "* " : "  ";
            System.out.println(marker + "🗂️ " + s.id() + " (" + s.model() + ", " + s.updatedAt() + ") " + s.preview());
        }
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
}
