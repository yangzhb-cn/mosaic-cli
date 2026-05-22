package com.corecoder.tools;

import com.corecoder.Agent;

import java.util.Map;

public final class AgentTool extends ToolBase {
    private final Agent parent;

    public AgentTool(Agent parent) {
        this.parent = parent;
    }

    @Override
    public String name() { return "Task"; }

    @Override
    public String description() { return "启动一个子 agent，独立处理复杂子任务。"; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of("task", prop("string", "子 agent 需要完成的任务")), "task");
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (parent == null) return "Error: agent tool not initialized (no parent agent)";
        try {
            String result = parent.runSubAgent(str(args, "task", ""), 20);
            if (result.length() > 5000) result = result.substring(0, 4500) + "\n... (sub-agent output truncated)";
            return "[Sub-agent completed]\n" + result;
        } catch (Exception e) {
            return "Sub-agent error: " + e.getMessage();
        }
    }
}
