package com.coder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.coder.im.ImClient;
import com.coder.tools.ToolExecutor;
import com.coder.tools.Tools;

public class Agent {
    final LlmClient llm;
    final List<Tools.Tool> tools;
    public final ContextManager context;
    // 对话消息历史
    public final List<Map<String, Object>> messages = new ArrayList<>();
    private final ToolExecutor toolExecutor;
    private final int maxRounds;
    private final String system;
    private final ImClient im;
    private volatile String currentImChatId;

    // 通用 Agent
    public Agent(LlmClient llm, int maxContextTokens) {
        this(llm, maxContextTokens, null);
    }

    public Agent(LlmClient llm, int maxContextTokens, ImClient im) {
        this.llm = llm;
        this.im = im;
        this.context = new ContextManager(maxContextTokens);
        // 最多允许模型-工具循环多少轮
        this.maxRounds = 50;
        this.tools = Tools.all(this);
        this.toolExecutor = new ToolExecutor(tools);
        this.system = Prompt.systemPrompt(tools);
    }

    //  子 Agen 构造函数
    Agent(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, int maxRounds) {
        this.llm = llm;
        this.im = null;
        this.tools = tools;
        this.toolExecutor = new ToolExecutor(tools);
        this.context = new ContextManager(maxContextTokens);
        this.maxRounds = maxRounds;
        this.system = Prompt.systemPrompt(tools);
    }

    // Agent 的主入口
    public synchronized String chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        // 1. 把用户消息加入历史
        messages.add(Map.of("role", "user", "content", userInput));
        // 2. 检查当前消息是否太长
        context.maybeCompress(messages, llm);
        // 3. 开始最多 maxRounds 轮的“模型 -> 工具 -> 模型”循环
        for (int i = 0; i < maxRounds; i++) {
            // 收集模型本轮生成的普通文本内容
            StringBuilder text = new StringBuilder();
            // 按工具 index 保存已经提前提交的执行任务。
            Map<Integer, Future<String>> futures = new LinkedHashMap<>();

            // 本轮工具执行共用一个固定线程池，最多并行 8 个工具。
            try (var pool = Executors.newFixedThreadPool(8)) {
                // 4. 调用大模型: 完整消息，包括 system prompt;工具 schema 列表;把流式返回的文本不断追加到 text
                LlmClient.Response r = llm.chat(fullMessages(), toolExecutor.schemas(), token -> {
                            text.append(token);
                            // 回调
                            if (onToken != null) onToken.accept(token);
                        },
                        // 某个流式工具参数拼完整后，立即提交到线程池执行。
                        (idx, tc) -> toolExecutor.submit(futures, pool, idx, tc, onTool));

                // 5a. 没有要求调用任何工具
                if (r.toolCalls().isEmpty()) {
                    // 把模型回复加入消息历史。
                    messages.add(r.message());
                    // 返回模型的最终内容，结束 chat
                    return r.content();
                }

                // 5b. 模型请求调用工具的情况
                // 保存工具调用指令tool_calls
                messages.add(r.message());

                // 等待提前提交的工具；没提前提交的工具在这里兜底提交。
                List<String> results = toolExecutor.collectResults(r.toolCalls(), futures, pool, onTool);
                // 遍历每一个工具调用，把执行结果写回消息历史
                for (int j = 0; j < r.toolCalls().size(); j++) {
                    // 使用 LinkedHashMap 是为了保持字段插入顺序，方便调试或序列化时更稳定
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "tool");
                    m.put("tool_call_id", r.toolCalls().get(j).id());
                    m.put("content", results.get(j));
                    messages.add(m);
                }
            }
            // 工具执行完之后，再检查一次消息是否过长。因为工具结果也可能很长
            context.maybeCompress(messages, llm);
        }
        return "(已达到最大工具调用轮数)";
    }

    public synchronized String chatFromIm(String chatId, String userInput) throws Exception {
        this.currentImChatId = chatId;
        return chat(userInput, null, null);
    }

    public ImClient imClient() {
        return im;
    }

    public String currentImChatId() {
        return currentImChatId;
    }

    public void setCurrentImChatId(String chatId) {
        this.currentImChatId = chatId;
    }

    // 清空对话历史
    public synchronized void reset() {
        messages.clear();
    }

    // 运行一个子 Agent 来处理某个任务
    public String runSubAgent(String task, int maxRounds) throws Exception {
        List<Tools.Tool> subTools = tools.stream()
                // 从当前工具列表里过滤掉名为 "Task" 的工具
                .filter(t -> !"Task".equals(t.name()))
                .toList();
        Agent sub = new Agent(llm, subTools, context.maxTokens, maxRounds);
        // 让子 Agent 执行任务，且不给 token 回调、工具回调
        return sub.chat(task, null, null);
    }

    // 构造发送给 LLM 的完整消息列表
    private List<Map<String, Object>> fullMessages() {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(Map.of("role", "system", "content", system));
        all.addAll(messages);
        return all;
    }

}
