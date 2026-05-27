package com.yang.tool;

import com.yang.agent.Agent;
import com.yang.schedule.ScheduleTools;
import com.yang.skill.Skill;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 注册内置工具、MCP/Skill 扩展工具，并保存工具共享状态。 */
public final class Tools {

    // 用于把 todo 列表转成 JSON 字符串
    private static final ObjectMapper JSON = new ObjectMapper();
    // 记录被修改过的文件路径
    private static final Set<String> CHANGED = new LinkedHashSet<>();
    // 保存待办事项列表
    private static final List<Map<String, Object>> TODOS = new ArrayList<>();

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
        return all(parent, List.of());
    }

    public static List<Tool> all(Agent parent, List<Tool> extraTools) {
        return all(parent, extraTools, List.of());
    }

    public static List<Tool> all(Agent parent, List<Tool> extraTools, List<Skill> skills) {
        List<Tool> tools = new ArrayList<>(List.of(
                new BashTool(),
                new GlobTool(),
                new GrepTool(),
                new LsTool(),
                new ReadFileTool(),
                new EditFileTool(),
                new MultiEditTool(),
                new WriteFileTool(),
                new WebFetchTool(),
                new WebSearchTool(),
                new TodoReadTool(),
                new TodoWriteTool(),
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

    // 记录修改过的文件
    public static Set<String> changedFiles() {
        return CHANGED;
    }

    public static void markChanged(Path path) {
        CHANGED.add(path.toString());
    }

    // 管理 todo 列表
    public static void replaceTodos(List<Map<String, Object>> todos) {
        // “当前状态”，不是增量累积
        TODOS.clear();
        TODOS.addAll(todos);
    }

    public static int todoCount() {
        return TODOS.size();
    }

    public static String todosJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(TODOS);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
