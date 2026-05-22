package com.corecoder.tools;

import com.corecoder.Agent;

import java.util.Map;

public final class AgentTool extends ToolBase {
    private final Agent parent;

    public AgentTool(Agent parent) {
        this.parent = parent;
    }

    @Override
    public String name() { return "agent"; }

    @Override
    public String description() { return "Spawn a sub-agent to handle a complex sub-task independently."; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of("task", prop("string", "What the sub-agent should accomplish")), "task");
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
