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
    public String description() { return "启动一个子智能体，独立处理复杂子任务。"; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of("task", prop("string", "子智能体需要完成的任务")), "task");
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (parent == null) return "错误: 智能体工具未初始化（没有父智能体）";
        try {
            String result = parent.runSubAgent(str(args, "task", ""), 20);
            if (result.length() > 5000) result = result.substring(0, 4500) + "\n... (子智能体输出已截断)";
            return "[子智能体已完成]\n" + result;
        } catch (Exception e) {
            return "子智能体错误: " + e.getMessage();
        }
    }
}
