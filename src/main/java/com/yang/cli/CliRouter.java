package com.yang.cli;

import com.yang.agent.Agent;
import com.yang.llm.LlmClient;
import com.yang.context.ContextManager;
import com.yang.mcp.McpManager;
import com.yang.session.SessionStore;
import com.yang.tool.Tools;
import org.jline.reader.Candidate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 路由斜杠命令到对应处理逻辑，保持 REPL 类只负责终端交互。 */
public final class CliRouter {
    private static final List<Candidate> COMMANDS = List.of(
            command("/reset", "清空当前对话"),
            command("/tokens", "查看 token 使用情况"),
            command("/compact", "压缩上下文"),
            command("/diff", "查看当前会话修改了哪些文件"),
            command("/mcp", "查看 MCP 加载状态"),
            command("/last-request", "查看上一轮发给 LLM 的完整 JSON 请求"),
            command("/plan", "进入计划生成模式"),
            command("/act", "执行当前计划"),
            command("/cancel", "取消当前计划"),
            command("/audit", "查看当前对话工具调用统计"),
            command("/audit save", "保存当前对话工具调用统计"),
            command("/save", "保存当前会话"),
            command("/load", "加载保存的会话"),
            command("/session", "查看当前会话用户消息"),
            command("/session list", "列出保存的会话"),
            command("/exit", "退出"));

    private CliRouter() {
    }

    public static List<Candidate> commands() {
        return COMMANDS;
    }

    private static Candidate command(String name, String description) {
        return new Candidate(name, name, null, description, null, null, true);
    }

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
            int before = ContextManager.estimateTokens(agent.messages);
            boolean changed = agent.context.maybeCompress(agent.messages, llm);
            int after = ContextManager.estimateTokens(agent.messages);
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
        if (line.equals("/last-request")) {
            String json = llm == null ? "" : llm.lastRequestJson();
            System.out.println(json == null || json.isBlank() ? "📭 暂无 LLM 请求。" : json);
            return true;
        }
        if (line.equals("/plan")) {
            agent.enterPlanMode();
            System.out.println("🧭 请输入要规划的任务。");
            return true;
        }
        if (line.startsWith("/plan ")) {
            System.out.println("暂不支持 /plan <task>，请先输入 /plan，再输入要规划的任务。");
            return true;
        }
        if (line.equals("/act")) {
            System.out.println(agent.actPlan());
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
        for (SessionStore.SessionInfo s : sessions.list()) {
            System.out.println("  🗂️ " + s.id() + " (" + s.model() + ", " + s.savedAt() + ") " + s.preview());
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
