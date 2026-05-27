package com.yang.session;

import com.yang.audit.ToolAudit;
import com.yang.llm.LlmClient;
import com.yang.memory.MemoryManager;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 维护当前运行时 session、audit 和长期记忆读写边界。 */
public final class SessionRuntime {
    private final LlmClient llm;
    private final ToolAudit audit;
    private final MemoryManager memory;
    private final SessionManager sessions;

    public SessionRuntime(LlmClient llm, ToolAudit audit, MemoryManager memory, SessionManager sessions, boolean archiveConversation) {
        this.llm = llm;
        this.audit = audit == null ? new ToolAudit() : audit;
        this.memory = memory == null ? MemoryManager.disabled() : memory;
        this.sessions = sessions == null ? SessionManager.disabled() : sessions;
    }

    public ToolAudit audit() {
        return audit;
    }

    public MemoryManager memory() {
        return memory;
    }

    public String conversationId() {
        return audit.conversationId();
    }

    public String sessionId() {
        return sessions.activeId();
    }

    public void archiveExchange(String userInput, String assistantResponse) {
    }

    public void resetAudit() {
        audit.reset();
    }

    public void resetSession() throws IOException {
        sessions.resetActive(audit.conversationId(), audit.records());
    }

    public void loadAudit(String conversationId, List<Map<String, Object>> auditRecords) {
        audit.restoreConversation(conversationId, auditRecords);
    }

    public void recordEvent(String kind, Map<String, Object> payload) {
        sessions.recordEvent(kind, payload);
    }

    public void save(List<Map<String, Object>> messages) throws IOException {
        sessions.saveActive(messages, llm.model, audit.conversationId(), audit.records());
    }

    public void saveQuietly(List<Map<String, Object>> messages) {
        try {
            save(messages);
        } catch (IOException ignored) {
        }
    }

    public String updateMemory(List<Map<String, Object>> messages) throws IOException {
        return memory.updateMemory(llm, messages) ? "🧠 长期记忆已更新: workspace/Mosaic.md" : "🧠 长期记忆未更新。";
    }
}
