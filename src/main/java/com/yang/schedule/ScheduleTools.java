package com.yang.schedule;

import com.yang.agent.Agent;
import com.yang.tool.ToolBase;
import com.yang.tool.Tools;

import java.util.List;
import java.util.Map;

/** 后台计划任务的模型可调用工具集合。 */
public final class ScheduleTools {
    private ScheduleTools() {
    }

    public static List<Tools.Tool> tools(Agent agent) {
        return List.of(
                new ScheduleTaskTool(agent),
                new ListScheduledTasksTool(agent),
                new PauseScheduledTaskTool(agent),
                new ResumeScheduledTaskTool(agent),
                new CancelScheduledTaskTool(agent)
        );
    }

    private abstract static class ScheduleTool extends ToolBase {
        final Agent agent;

        ScheduleTool(Agent agent) {
            this.agent = agent;
        }

        ScheduleStore store() {
            if (agent == null || agent.scheduleStore() == null) throw new IllegalStateException("Schedule 未启用");
            return agent.scheduleStore();
        }
    }

    private static final class ScheduleTaskTool extends ScheduleTool {
        ScheduleTaskTool(Agent agent) {
            super(agent);
        }

        @Override
        public String name() {
            return "schedule_task";
        }

        @Override
        public String description() {
            return "创建后台计划任务。用于提醒、一次性后台任务或固定间隔周期任务；不同于 Todo。";
        }

        @Override
        public Map<String, Object> parameters() {
            return params(Map.of(
                    "prompt", prop("string", "后台任务要执行的具体内容"),
                    "schedule_type", prop("string", "调度类型：once 或 interval"),
                    "run_at", prop("string", "once 任务执行时间，ISO 格式，例如 2026-05-27T09:00:00+08:00"),
                    "interval_seconds", prop("integer", "interval 任务间隔秒数，必须大于 0")
            ), "prompt", "schedule_type");
        }

        @Override
        public String execute(Map<String, Object> args) {
            try {
                String prompt = str(args, "prompt", "").strip();
                if (prompt.isBlank()) return "错误: prompt 不能为空";
                String type = ScheduleStore.normalizeType(str(args, "schedule_type", ""));
                String runAt = str(args, "run_at", "");
                Integer interval = args.containsKey("interval_seconds") ? integer(args, "interval_seconds", 0) : null;
                String chatId = agent == null ? "" : agent.currentImChatId();
                ScheduledTask task = store().create(prompt, type, runAt, interval, chatId);
                return "已创建计划任务: " + task.id() + " (" + task.type() + ", next_run=" + task.nextRun() + ")";
            } catch (Exception e) {
                return "错误: " + e.getMessage();
            }
        }
    }

    private static final class ListScheduledTasksTool extends ScheduleTool {
        ListScheduledTasksTool(Agent agent) {
            super(agent);
        }

        @Override
        public String name() {
            return "list_scheduled_tasks";
        }

        @Override
        public String description() {
            return "列出后台计划任务，可按 status 过滤。";
        }

        @Override
        public Map<String, Object> parameters() {
            return params(Map.of("status", prop("string", "可选状态：active、paused、completed、canceled、error")), new String[0]);
        }

        @Override
        public String execute(Map<String, Object> args) {
            try {
                List<ScheduledTask> tasks = store().list(str(args, "status", ""));
                if (tasks.isEmpty()) return "暂无计划任务";
                StringBuilder out = new StringBuilder();
                for (ScheduledTask task : tasks) {
                    out.append(task.id()).append("  ")
                            .append(task.status()).append("  ")
                            .append(task.type()).append("  next=")
                            .append(task.nextRun() == null ? "" : task.nextRun())
                            .append("  ")
                            .append(task.prompt())
                            .append('\n');
                }
                return out.toString().stripTrailing();
            } catch (Exception e) {
                return "错误: " + e.getMessage();
            }
        }
    }

    private static final class PauseScheduledTaskTool extends ScheduleTool {
        PauseScheduledTaskTool(Agent agent) {
            super(agent);
        }

        @Override
        public String name() {
            return "pause_scheduled_task";
        }

        @Override
        public String description() {
            return "暂停 active 或 error 状态的后台计划任务。";
        }

        @Override
        public Map<String, Object> parameters() {
            return params(Map.of("id", prop("string", "计划任务 id")), "id");
        }

        @Override
        public String execute(Map<String, Object> args) {
            try {
                return store().pause(str(args, "id", "")) ? "计划任务已暂停" : "错误: 未找到可暂停的计划任务";
            } catch (Exception e) {
                return "错误: " + e.getMessage();
            }
        }
    }

    private static final class ResumeScheduledTaskTool extends ScheduleTool {
        ResumeScheduledTaskTool(Agent agent) {
            super(agent);
        }

        @Override
        public String name() {
            return "resume_scheduled_task";
        }

        @Override
        public String description() {
            return "恢复 paused 或 error 状态的后台计划任务，并重新计算 next_run。";
        }

        @Override
        public Map<String, Object> parameters() {
            return params(Map.of("id", prop("string", "计划任务 id")), "id");
        }

        @Override
        public String execute(Map<String, Object> args) {
            try {
                return store().resume(str(args, "id", "")) ? "计划任务已恢复" : "错误: 未找到可恢复的计划任务";
            } catch (Exception e) {
                return "错误: " + e.getMessage();
            }
        }
    }

    private static final class CancelScheduledTaskTool extends ScheduleTool {
        CancelScheduledTaskTool(Agent agent) {
            super(agent);
        }

        @Override
        public String name() {
            return "cancel_scheduled_task";
        }

        @Override
        public String description() {
            return "取消后台计划任务。任务不会物理删除，会保留最后执行结果。";
        }

        @Override
        public Map<String, Object> parameters() {
            return params(Map.of("id", prop("string", "计划任务 id")), "id");
        }

        @Override
        public String execute(Map<String, Object> args) {
            try {
                return store().cancel(str(args, "id", "")) ? "计划任务已取消" : "错误: 未找到计划任务";
            } catch (Exception e) {
                return "错误: " + e.getMessage();
            }
        }
    }
}
