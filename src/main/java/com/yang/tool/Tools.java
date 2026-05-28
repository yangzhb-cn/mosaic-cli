package com.yang.tool;

import com.yang.agent.Agent;
import com.yang.schedule.ScheduleTools;
import com.yang.skill.Skill;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 注册内置工具、MCP/Skill 扩展工具。 */
public final class Tools {
    // 禁止外部实例化，说明它是一个纯工具类
    private Tools() {
    }

    /** 模型可调用工具的最小接口。 */
    public interface Tool {
        // 工具名
        String name();
        // 工具说明
        String description();
        //  JSON Schema 风格参数
        Map<String, Object> parameters();
        // 真正执行工具逻辑
        String execute(Map<String, Object> args);

        // 把工具包装成统一的函数调用格式
        default Map<String, Object> schema() {
            return Map.of("type", "function", "function", Map.of(
                    "name", name(),
                    "description", description(),
                    "parameters", parameters()
            ));
        }
    }

    // 注册全部工具
    public static List<Tool> all(Agent parent) {
        return all(parent, List.of(), List.of(), new ToolState());
    }

    public static List<Tool> all(Agent parent, List<Tool> extraTools) {
        return all(parent, extraTools, List.of(), new ToolState());
    }

    public static List<Tool> all(Agent parent, List<Tool> extraTools, List<Skill> skills) {
        return all(parent, extraTools, skills, new ToolState());
    }

    public static List<Tool> all(Agent parent, List<Tool> extraTools, List<Skill> skills, ToolState state) {
        ToolState toolState = state == null ? new ToolState() : state;
        List<Tool> tools = new ArrayList<>(List.of(
                new BashTool(),
                new GlobTool(),
                new GrepTool(),
                new LsTool(),
                new ReadFileTool(),
                new EditFileTool(toolState),
                new MultiEditTool(toolState),
                new WriteFileTool(toolState),
                new WebFetchTool(),
                new WebSearchTool(),
                new TodoReadTool(toolState),
                new TodoWriteTool(toolState),
                new AgentTool(parent)
        ));
        tools.addAll(ScheduleTools.tools(parent));
        if (parent != null && parent.imClient() != null) tools.add(new SendMessageTool(parent));
        if (!skills.isEmpty()) tools.add(new ReadSkillTool(skills));
        tools.addAll(extraTools);
        return List.copyOf(tools);
    }

    // 获取tools的名字
    public static Set<String> names(List<Tool> tools) {
        Set<String> names = new LinkedHashSet<>();
        for (Tool tool : tools) names.add(tool.name());
        return names;
    }

    // 按名字查工具
    public static Tool get(List<Tool> tools, String name) {
        for (Tool t : tools) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }
}
