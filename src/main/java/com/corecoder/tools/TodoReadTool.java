package com.corecoder.tools;

import java.util.Map;

public final class TodoReadTool extends ToolBase {
    @Override
    public String name() { return "TodoRead"; }

    @Override
    public String description() { return "读取当前会话的待办列表。"; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        return Tools.todosJson();
    }
}
