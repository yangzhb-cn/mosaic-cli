package com.corecoder.tools;

import java.util.List;
import java.util.Map;

public final class TodoWriteTool extends ToolBase {
    @Override
    public String name() { return "TodoWrite"; }

    @Override
    public String description() { return "创建或替换当前会话的 Todo 列表。"; }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> todo = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "id", prop("string", "稳定的 Todo ID"),
                        "content", prop("string", "Todo 内容"),
                        "status", prop("string", "状态：pending、in_progress 或 completed"),
                        "priority", prop("string", "优先级：high、medium 或 low")
                ),
                "required", List.of("id", "content", "status", "priority")
        );
        return params(Map.of("todos", arrayProp("更新后的 Todo 列表", todo)), "todos");
    }

    @Override
    public String execute(Map<String, Object> args) {
        Tools.replaceTodos(mapList(args.get("todos")));
        return "Todos updated: " + Tools.todoCount();
    }
}
