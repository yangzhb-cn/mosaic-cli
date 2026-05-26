package com.yang.tool;

import java.util.List;
import java.util.Map;

// 读取当前 JVM 会话里的 todo 列表
public final class TodoReadTool extends ToolBase {
    @Override
    public String name() { return "TodoRead"; }

    @Override
    public String description() {
        return """
                使用此工具读取当前会话的待办列表。应主动且频繁使用此工具，确保你了解当前任务列表状态。你应尽可能多地使用此工具，尤其在以下情况：
                - 会话开始时查看待处理事项
                - 开始新任务前确定优先级
                - 用户询问之前的任务或计划时
                - 当你不确定下一步做什么时
                - 完成任务后更新对剩余工作的理解
                - 每隔几条消息后确保你仍在正确轨道上

                使用：
                - 此工具不需要参数。因此输入留空即可。不要包含占位对象、占位字符串或类似 "input"、"empty" 的 key。保持空白。
                - 返回包含 status、priority 和 content 的待办项列表
                - 使用这些信息跟踪进度并规划下一步
                - 如果还没有 todos，将返回空列表
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", "无需输入，保持此字段为空。注意，我们不需要占位对象、占位字符串或类似 \"input\"、\"empty\" 的 key。保持空白。",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        return Tools.todosJson();
    }
}
