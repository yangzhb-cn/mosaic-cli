package com.yang.tool;

import java.util.Map;

import com.yang.Agent;

// 	IM 模式下主动给当前 Telegram 会话发消息
public final class SendMessageTool extends ToolBase {
    private final Agent agent;

    public SendMessageTool(Agent agent) {
        this.agent = agent;
    }

    @Override
    public String name() { return "send_message"; }

    @Override
    public String description() {
        return "向当前 IM 会话主动发送消息。适合通知用户任务结果或重要进展。";
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of("text", prop("string", "要发送给用户的文本消息")), "text");
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (agent == null || agent.imClient() == null) return "错误: IM 未启用，无法发送消息";
        String chatId = agent.currentImChatId();
        if (chatId == null || chatId.isBlank()) return "错误: 当前没有可用的 IM 会话";
        String text = str(args, "text", "");
        if (text.isBlank()) return "错误: 消息内容不能为空";
        try {
            agent.imClient().send(chatId, text);
            return "消息已发送";
        } catch (Exception e) {
            return "错误: 消息发送失败: " + e.getMessage();
        }
    }
}
