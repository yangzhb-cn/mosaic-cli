package com.coder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 管理对话上下文长度，在上下文过大时按层级压缩历史消息。
 */
public class ContextManager {
    /** 对话允许的最大估算 token 数。 */
    public final int maxTokens;

    /** 第一档压缩阈值：超过后先截断过长工具输出。 */
    private final int snipAt;

    /** 第二档压缩阈值：超过后摘要较早对话。 */
    private final int summarizeAt;

    /** 第三档压缩阈值：超过后执行更激进的强制压缩。 */
    private final int collapseAt;

    /**
     * 根据最大上下文窗口计算三档压缩阈值。
     */
    public ContextManager(int maxTokens) {
        // 保存调用方传入的最大上下文估算值。
        this.maxTokens = maxTokens;

        // 50% 时先压缩工具输出，成本最低。
        this.snipAt = (int) (maxTokens * 0.50);

        // 70% 时摘要旧对话，保留最近几轮原文。
        this.summarizeAt = (int) (maxTokens * 0.70);

        // 90% 时强制压缩，避免下一轮请求超出上下文窗口。
        this.collapseAt = (int) (maxTokens * 0.90);
    }

    /**
     * 粗略估算消息列表占用的 token 数。
     */
    public static int estimateTokens(List<Map<String, Object>> messages) {
        // 累计所有消息文本和工具调用的估算 token。
        int total = 0;

        // 逐条处理上下文消息。
        for (Map<String, Object> m : messages) {
            // 读取普通文本内容。
            Object content = m.get("content");

            // 按“约 3 个字符 1 个 token”的简化规则累计文本。
            if (content != null) {
                total += String.valueOf(content).length() / 3;
            }

            // 读取 assistant 消息里可能携带的 tool_calls。
            Object tools = m.get("tool_calls");

            // tool_calls 也会进入请求上下文，所以一起估算。
            if (tools != null) {
                total += String.valueOf(tools).length() / 3;
            }
        }

        // 返回当前上下文的估算 token 总量。
        return total;
    }

    /**
     * 按 50% / 70% / 90% 三档策略尝试压缩上下文。
     */
    public boolean maybeCompress(List<Map<String, Object>> messages, LlmClient llm) {
        // 先估算当前上下文大小。
        int current = estimateTokens(messages);

        // 标记本次调用是否真的改动了消息列表。
        boolean changed = false;

        // 超过 50% 时优先截断长工具输出。
        if (current > snipAt && snipToolOutputs(messages)) {
            // 记录本轮已经改动上下文。
            changed = true;

            // 截断后重新估算，决定是否还需要进入下一档。
            current = estimateTokens(messages);
        }

        // 超过 70% 且历史足够长时，摘要旧消息并保留最近 8 条。
        if (current > summarizeAt && messages.size() > 10 && summarizeOld(messages, llm, 8)) {
            // 记录本轮已经改动上下文。
            changed = true;

            // 摘要后重新估算，决定是否还需要强制压缩。
            current = estimateTokens(messages);
        }

        // 超过 90% 且仍有足够历史时，执行强制压缩。
        if (current > collapseAt && messages.size() > 4) {
            // 用更激进方式压缩旧上下文。
            hardCollapse(messages, llm);

            // 记录本轮已经改动上下文。
            changed = true;
        }

        // 告诉调用方是否发生了压缩。
        return changed;
    }

    /**
     * 截断过长的 tool 消息，只保留头尾几行。
     */
    private boolean snipToolOutputs(List<Map<String, Object>> messages) {
        // 标记是否有工具输出被截断。
        boolean changed = false;

        // 遍历所有历史消息。
        for (Map<String, Object> m : messages) {
            // 只处理工具结果消息。
            if (!"tool".equals(m.get("role"))) {
                continue;
            }

            // 取出工具返回内容，缺失时按空字符串处理。
            String content = String.valueOf(m.getOrDefault("content", ""));

            // 1500 字符以内认为无需截断。
            if (content.length() <= 1500) {
                continue;
            }

            // 按行切分，便于保留开头和结尾。
            String[] lines = content.split("\\R");

            // 行数太少时不截断，避免丢掉主要信息。
            if (lines.length <= 6) {
                continue;
            }

            // 构造截断后的工具输出。
            StringBuilder s = new StringBuilder();

            // 保留前三行，通常包含命令或结果开头。
            for (int i = 0; i < 3; i++) {
                s.append(lines[i]).append('\n');
            }

            // 写入中间省略提示，明确说明这是上下文压缩导致的截断。
            s.append("... (").append(lines.length).append(" 行，为节省上下文已截断) ...\n");

            // 保留最后三行，通常包含错误尾部或总结信息。
            for (int i = Math.max(3, lines.length - 3); i < lines.length; i++) {
                s.append(lines[i]).append('\n');
            }

            // 用截断后的内容覆盖原工具输出。
            m.put("content", s.toString().stripTrailing());

            // 标记本轮发生过截断。
            changed = true;
        }

        // 返回是否有任何工具输出被截断。
        return changed;
    }

    /**
     * 将较早的消息压缩成摘要，并保留最近 keepRecent 条消息原文。
     */
    private boolean summarizeOld(List<Map<String, Object>> messages, LlmClient llm, int keepRecent) {
        // 如果消息数量不足，不需要也不能摘要旧历史。
        if (messages.size() <= keepRecent) {
            return false;
        }

        // 拷贝需要被摘要的旧消息。
        List<Map<String, Object>> old = new ArrayList<>(messages.subList(0, messages.size() - keepRecent));

        // 拷贝最近消息，保持它们的原始细节。
        List<Map<String, Object>> tail = new ArrayList<>(messages.subList(messages.size() - keepRecent, messages.size()));

        // 清空原上下文，准备写入压缩后的结构。
        messages.clear();

        // 用一条 user 消息承载旧上下文摘要。
        messages.add(Map.of("role", "user", "content", "[上下文已压缩 - 对话摘要]\n" + summary(old, llm)));

        // 加一条 assistant 确认消息，让对话角色顺序自然衔接。
        messages.add(Map.of("role", "assistant", "content", "收到，我已保留前面对话的上下文。"));

        // 追加最近消息原文，保证当前任务连续性。
        messages.addAll(tail);

        // 返回 true 表示消息列表已被改写。
        return true;
    }

    /**
     * 在上下文接近上限时执行强制压缩，只保留更少的最近消息。
     */
    private void hardCollapse(List<Map<String, Object>> messages, LlmClient llm) {
        // 消息足够多时保留最后 4 条，否则至少保留 2 条。
        int keep = messages.size() > 4 ? 4 : 2;

        // 拷贝需要被强制摘要的旧消息。
        List<Map<String, Object>> old = new ArrayList<>(messages.subList(0, messages.size() - keep));

        // 拷贝最后几条消息，保留当前对话现场。
        List<Map<String, Object>> tail = new ArrayList<>(messages.subList(messages.size() - keep, messages.size()));

        // 清空原上下文，准备写入更短的上下文。
        messages.clear();

        // 用强制重置摘要替代大段旧历史。
        messages.add(Map.of("role", "user", "content", "[上下文强制重置]\n" + summary(old, llm)));

        // 加 assistant 衔接语，避免下一轮模型看到孤立摘要。
        messages.add(Map.of("role", "assistant", "content", "上下文已恢复，继续之前的任务。"));

        // 追加最后几条原始消息。
        messages.addAll(tail);
    }

    /**
     * 优先用 LLM 摘要上下文；失败时退回本地关键信息提取。
     */
    private String summary(List<Map<String, Object>> messages, LlmClient llm) {
        // 只有传入 LLM 客户端时才尝试模型摘要。
        if (llm != null) {
            // 模型摘要可能失败，失败后走本地兜底。
            try {
                // 请求 LLM 生成简短摘要，并要求保留关键工程上下文。
                LlmClient.Response r = llm.chat(List.of(
                        Map.of("role", "system", "content", "把这段对话压缩成简短摘要。保留文件路径、决策、错误和当前任务状态。"),
                        Map.of("role", "user", "content", flatten(messages, 15000))
                ), null, null, null);

                // 如果模型返回非空摘要，就直接使用。
                if (!r.content().isBlank()) {
                    return r.content();
                }
            } catch (Exception ignored) {
                // 摘要失败不影响主流程，后面使用本地提取兜底。
            }
        }

        // 没有 LLM 或 LLM 失败时，提取文件路径和错误信息作为摘要。
        return extract(messages);
    }

    /**
     * 将多条消息压平成有限长度的纯文本，供摘要模型读取。
     */
    private static String flatten(List<Map<String, Object>> messages, int limit) {
        // 保存压平后的文本。
        StringBuilder out = new StringBuilder();

        // 逐条消息拼接 role 和内容片段。
        for (Map<String, Object> m : messages) {
            // 先写消息角色，方便摘要模型区分说话方。
            out.append('[').append(m.getOrDefault("role", "?")).append("] ");

            // 取出消息内容；一条消息最多保留 400 字符。
            out.append(String.valueOf(m.getOrDefault("content", "")), 0, Math.min(400, String.valueOf(m.getOrDefault("content", "")).length()));

            // 每条消息后换行，保持可读性。
            out.append('\n');

            // 达到总长度上限就提前停止。
            if (out.length() >= limit) {
                break;
            }
        }

        // 返回不超过 limit 的压平文本。
        return out.substring(0, Math.min(out.length(), limit));
    }

    /**
     * 本地兜底摘要：提取文件路径和错误行。
     */
    private static String extract(List<Map<String, Object>> messages) {
        // 匹配常见文件路径或文件名。
        Pattern file = Pattern.compile("[\\w./-]+\\.\\w{1,5}");

        // 用有序集合去重并保持首次出现顺序。
        LinkedHashSet<String> files = new LinkedHashSet<>();

        // 保存最多几条错误相关文本。
        List<String> errors = new ArrayList<>();

        // 遍历所有消息内容。
        for (Map<String, Object> m : messages) {
            // 取出消息文本，缺失时按空字符串处理。
            String text = String.valueOf(m.getOrDefault("content", ""));

            // 从文本中提取最多 20 个文件路径。
            file.matcher(text).results().limit(20).forEach(r -> files.add(r.group()));

            // 按行扫描错误信息。
            for (String line : text.split("\\R")) {
                // 包含 error 的行视为错误线索。
                if (line.toLowerCase().contains("error")) {
                    errors.add(line.strip());
                }

                // 错误线索最多保留 5 条，避免兜底摘要太长。
                if (errors.size() >= 5) {
                    break;
                }
            }
        }

        // 组装兜底摘要片段。
        List<String> parts = new ArrayList<>();

        // 有文件线索时写入摘要。
        if (!files.isEmpty()) {
            parts.add("涉及文件: " + String.join(", ", files));
        }

        // 有错误线索时写入摘要。
        if (!errors.isEmpty()) {
            parts.add("发现错误: " + String.join("; ", errors));
        }

        // 没有提取到信息时返回明确占位。
        return parts.isEmpty() ? "(没有可提取的上下文)" : String.join("\n", parts);
    }
}
