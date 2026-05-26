package com.yang.tool;

import java.util.Map;

import com.yang.Agent;

// 启动子 Agent 做子任务，子 Agent 不再拥有 Task 工具
public final class AgentTool extends ToolBase {
    private final Agent parent;

    public AgentTool(Agent parent) {
        this.parent = parent;
    }

    @Override
    public String name() { return "Task"; }

    @Override
    public String description() {
        return """
                启动一个新 agent，该 agent 可访问以下工具：Bash、Glob、Grep、LS、Read、Edit、MultiEdit、Write、TodoRead、TodoWrite。当你搜索关键字或文件，并且不确定前几次尝试能否找到正确匹配时，使用 Agent 工具替你搜索。

                何时使用 Agent 工具：
                - 如果你搜索的是类似 "config" 或 "logger" 的关键字，或类似“哪个文件做了 X？”的问题，强烈建议使用 Agent 工具

                何时不使用 Agent 工具：
                - 如果你要读取具体文件路径，使用 Read 或 Glob 工具而不是 Agent 工具，这样更快
                - 如果你搜索的是特定类定义，例如 "class Foo"，使用 Glob 工具，这样更快
                - 如果你只在某个具体文件或 2-3 个文件中搜索代码，使用 Read 工具，这样更快

                使用说明：
                1. 尽可能并发启动多个 agent，以最大化性能；要做到这一点，在一条消息中使用多个工具调用
                2. agent 完成后会返回一条消息给你。agent 返回的结果对用户不可见。要向用户展示结果，你应该发送一条文本消息，简要总结结果。
                3. 每次 agent 调用都是无状态的。你不能继续给 agent 发送额外消息，agent 也不能在最终报告之外与你通信。因此你的任务描述应该包含非常详细的任务说明，让 agent 能自主执行，并且应明确指定 agent 在唯一最终消息中要返回哪些信息。
                4. agent 输出通常应被信任
                5. 明确告诉 agent 你期望它写代码，还是只做研究（搜索、读取文件等）
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of("task", prop("string", "agent 要执行的任务")), "task");
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
