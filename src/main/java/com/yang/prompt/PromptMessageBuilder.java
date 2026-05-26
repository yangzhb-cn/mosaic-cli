package com.yang.prompt;

import com.yang.memory.MemoryManager;
import com.yang.skill.Skill;
import com.yang.tool.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构造发给 LLM 的 system prompt 和带动态 system-reminder 的消息列表。 */
public final class PromptMessageBuilder {
    private final String system;
    private final List<Tools.Tool> mcpTools;
    private final List<Skill> skills;
    private final MemoryManager memory;

    public PromptMessageBuilder(List<Tools.Tool> tools, List<Skill> skills, MemoryManager memory) {
        this.skills = List.copyOf(skills);
        this.memory = memory;
        this.mcpTools = tools.stream().filter(PromptMessageBuilder::isMcpTool).toList();
        this.system = Prompt.systemPrompt(tools.stream().filter(t -> !isMcpTool(t)).toList(), this.skills);
    }

    public List<Map<String, Object>> fullMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(Map.of("role", "system", "content", system));
        for (Map<String, Object> message : messages) {
            all.add(new LinkedHashMap<>(message));
        }
        injectSystemReminder(all);
        return all;
    }

    private void injectSystemReminder(List<Map<String, Object>> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            Map<String, Object> message = all.get(i);
            if (!"user".equals(message.get("role"))) continue;
            Object content = message.get("content");
            String reminder = Prompt.systemReminder(mcpTools, skills, memory.readMemory());
            if (!reminder.isBlank()) message.put("content", reminder + "\n\n" + (content == null ? "" : content));
            return;
        }
    }

    private static boolean isMcpTool(Tools.Tool tool) {
        return tool.name().startsWith("mcp_");
    }
}
