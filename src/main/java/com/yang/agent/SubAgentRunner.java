package com.yang.agent;

import com.yang.audit.ToolAudit;
import com.yang.llm.LlmClient;
import com.yang.memory.MemoryManager;
import com.yang.session.SessionManager;
import com.yang.skill.Skill;
import com.yang.tool.Tools;

import java.util.List;

/** 创建无状态子 Agent，并避免子 Agent 再次暴露 Task 工具或写入顶层对话归档。 */
final class SubAgentRunner {
    private final LlmClient llm;
    private final List<Tools.Tool> tools;
    private final int maxContextTokens;
    private final List<Skill> skills;
    private final ToolAudit audit;
    private final MemoryManager memory;

    SubAgentRunner(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, List<Skill> skills, ToolAudit audit, MemoryManager memory) {
        this.llm = llm;
        this.tools = tools;
        this.maxContextTokens = maxContextTokens;
        this.skills = skills;
        this.audit = audit;
        this.memory = memory;
    }

    String run(String task, int maxRounds) throws Exception {
        List<Tools.Tool> subTools = tools.stream()
                .filter(t -> !"Task".equals(t.name()))
                .toList();
        Agent sub = new Agent(llm, subTools, maxContextTokens, maxRounds, skills, audit, memory, SessionManager.disabled(), false);
        return sub.chat(task, null, null);
    }
}
