package com.yang.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.io.IOException;

import com.yang.audit.ToolAudit;
import com.yang.cli.CliPlanController;
import com.yang.context.ContextManager;
import com.yang.im.ImClient;
import com.yang.im.ImRuntime;
import com.yang.llm.LlmClient;
import com.yang.memory.MemoryManager;
import com.yang.plan.PlanRunner;
import com.yang.plan.PlanSession;
import com.yang.plan.PlannerAgent;
import com.yang.prompt.PromptMessageBuilder;
import com.yang.schedule.ScheduleStore;
import com.yang.session.SessionManager;
import com.yang.session.SessionRuntime;
import com.yang.skill.Skill;
import com.yang.tool.ToolExecutor;
import com.yang.tool.Tools;

/** 核心 ReAct Agent，维护会话消息并协调 LLM、工具、上下文压缩和子 Agent。 */
public class Agent {
    final LlmClient llm;
    final List<Tools.Tool> tools;
    public final ContextManager context;
    // 对话消息历史
    public final List<Map<String, Object>> messages = new ArrayList<>();
    private final ToolExecutor toolExecutor;
    private final int maxRounds;
    private final SessionRuntime session;
    private final ImRuntime im;
    private final ScheduleStore scheduleStore;
    private final PromptMessageBuilder promptBuilder;
    private final SubAgentRunner subAgents;
    private final CliPlanController planController;
    private TokenUsage lastTokenUsage = new TokenUsage(0, 0, 0, 0, 0);

    /** 记录最近一次回复的 token 用量和上下文占用比例。 */
    public record TokenUsage(int promptTokens, int cachedPromptTokens, int completionTokens, int contextTokens, int maxContextTokens) {
        public int contextPercent() {
            if (maxContextTokens <= 0) return 0;
            return Math.min(100, Math.round(contextTokens * 100f / maxContextTokens));
        }
    }

    // 通用 Agent
    public Agent(LlmClient llm, int maxContextTokens) {
        this(llm, maxContextTokens, null);
    }

    public Agent(LlmClient llm, int maxContextTokens, ImClient im) {
        this(llm, maxContextTokens, im, List.of(), List.of());
    }

    public Agent(LlmClient llm, int maxContextTokens, ImClient im, List<Tools.Tool> extraTools, List<Skill> skills) {
        this(llm, maxContextTokens, im, extraTools, skills, new ToolAudit());
    }

    public Agent(LlmClient llm, int maxContextTokens, ImClient im, List<Tools.Tool> extraTools, List<Skill> skills, ToolAudit audit) {
        this(llm, maxContextTokens, im, extraTools, skills, audit, MemoryManager.disabled());
    }

    public Agent(LlmClient llm, int maxContextTokens, ImClient im, List<Tools.Tool> extraTools, List<Skill> skills, ToolAudit audit, MemoryManager memory) {
        this(llm, maxContextTokens, im, extraTools, skills, audit, memory, SessionManager.disabled());
    }

    public Agent(LlmClient llm, int maxContextTokens, ImClient im, List<Tools.Tool> extraTools, List<Skill> skills, ToolAudit audit, MemoryManager memory, SessionManager sessionManager) {
        this(llm, maxContextTokens, im, extraTools, skills, audit, memory, sessionManager, ScheduleStore.disabled());
    }

    public Agent(LlmClient llm, int maxContextTokens, ImClient im, List<Tools.Tool> extraTools, List<Skill> skills, ToolAudit audit, MemoryManager memory, SessionManager sessionManager, ScheduleStore scheduleStore) {
        List<Skill> skillList = List.copyOf(skills);
        this.llm = llm;
        this.im = new ImRuntime(im);
        this.scheduleStore = scheduleStore == null ? ScheduleStore.disabled() : scheduleStore;
        this.session = new SessionRuntime(llm, audit, memory, sessionManager, true);
        this.context = new ContextManager(maxContextTokens);
        // 最多允许模型-工具循环多少轮
        this.maxRounds = 50;
        this.tools = Tools.all(this, extraTools, skillList);
        this.toolExecutor = new ToolExecutor(tools, this.session.audit());
        this.promptBuilder = new PromptMessageBuilder(tools, skillList, this.session.memory());
        this.subAgents = new SubAgentRunner(llm, tools, context.maxTokens, skillList, this.session.audit(), this.session.memory());
        this.planController = new CliPlanController(new PlannerAgent(llm, this.session.audit()), new PlanRunner(this), messages, this::messagesChanged, llm);
    }

    //  子 Agen 构造函数
    Agent(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, int maxRounds) {
        this(llm, tools, maxContextTokens, maxRounds, List.of());
    }

    Agent(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, int maxRounds, List<Skill> skills) {
        this(llm, tools, maxContextTokens, maxRounds, skills, new ToolAudit());
    }

    Agent(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, int maxRounds, List<Skill> skills, ToolAudit audit) {
        this(llm, tools, maxContextTokens, maxRounds, skills, audit, MemoryManager.disabled());
    }

    Agent(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, int maxRounds, List<Skill> skills, ToolAudit audit, MemoryManager memory) {
        this(llm, tools, maxContextTokens, maxRounds, skills, audit, memory, SessionManager.disabled());
    }

    Agent(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, int maxRounds, List<Skill> skills, ToolAudit audit, MemoryManager memory, SessionManager sessionManager) {
        this(llm, tools, maxContextTokens, maxRounds, skills, audit, memory, sessionManager, true);
    }

    Agent(LlmClient llm, List<Tools.Tool> tools, int maxContextTokens, int maxRounds, List<Skill> skills, ToolAudit audit, MemoryManager memory, SessionManager sessionManager, boolean archiveConversation) {
        List<Skill> skillList = List.copyOf(skills);
        this.llm = llm;
        this.im = new ImRuntime(null);
        this.scheduleStore = ScheduleStore.disabled();
        this.tools = tools;
        this.context = new ContextManager(maxContextTokens);
        this.maxRounds = maxRounds;
        this.session = new SessionRuntime(llm, audit, memory, sessionManager, archiveConversation);
        this.toolExecutor = new ToolExecutor(tools, this.session.audit());
        this.promptBuilder = new PromptMessageBuilder(tools, skillList, this.session.memory());
        this.subAgents = new SubAgentRunner(llm, tools, context.maxTokens, skillList, this.session.audit(), this.session.memory());
        this.planController = new CliPlanController(new PlannerAgent(llm, this.session.audit()), new PlanRunner(this), messages, this::messagesChanged, llm);
    }

    public synchronized String chatCli(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        return planController.chatCli(userInput, onToken, onTool, this::chat);
    }

    // Agent 的主入口
    public synchronized String chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        // 1. 把用户消息加入历史
        messages.add(Map.of("role", "user", "content", userInput));
        // 2. 检查当前消息是否太长
        context.maybeCompress(messages, llm);
        int promptTokens = 0;
        int cachedPromptTokens = 0;
        int completionTokens = 0;
        int contextTokens = 0;
        // 3. 开始最多 maxRounds 轮的“模型 -> 工具 -> 模型”循环
        for (int i = 0; i < maxRounds; i++) {
            // 收集模型本轮生成的普通文本内容
            StringBuilder text = new StringBuilder();
            // 按工具 index 保存已经提前提交的执行任务。
            Map<Integer, Future<String>> futures = new LinkedHashMap<>();

            // 本轮工具执行共用一个固定线程池，最多并行 8 个工具。
            try (var pool = toolPool()) {
                // 4. 调用大模型: 完整消息，包括 system prompt;工具 schema 列表;把流式返回的文本不断追加到 text
                LlmClient.Response r = llm.chat(promptBuilder.fullMessages(messages), toolExecutor.schemas(), token -> {
                            text.append(token);
                            // 回调
                            if (onToken != null) onToken.accept(token);
                        },
                        // 某个流式工具参数拼完整后，立即提交到线程池执行。
                        (idx, tc) -> toolExecutor.submit(futures, pool, idx, tc, toolCallback(onTool)));
                promptTokens += r.promptTokens();
                cachedPromptTokens += r.cachedPromptTokens();
                completionTokens += r.completionTokens();
                if (r.promptTokens() > 0) contextTokens = r.promptTokens();

                // 5a. 没有要求调用任何工具
                if (r.toolCalls().isEmpty()) {
                    // 把模型回复加入消息历史。
                    messages.add(r.message());
                    lastTokenUsage = usage(promptTokens, cachedPromptTokens, completionTokens, contextTokens);
                    saveSession();
                    // 返回模型的最终内容，结束 chat
                    return r.content();
                }

                // 5b. 模型请求调用工具的情况
                // 保存工具调用指令tool_calls
                messages.add(r.message());

                // 等待提前提交的工具；没提前提交的工具在这里兜底提交。
                List<String> results = toolExecutor.collectResults(r.toolCalls(), futures, pool, toolCallback(onTool));
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
        lastTokenUsage = usage(promptTokens, cachedPromptTokens, completionTokens, contextTokens);
        saveSession();
        return "⚠️ 已达到最大工具调用轮数。";
    }

    private TokenUsage usage(int promptTokens, int cachedPromptTokens, int completionTokens, int contextTokens) {
        int current = contextTokens > 0 ? contextTokens : ContextManager.estimateTokens(messages);
        return new TokenUsage(promptTokens, cachedPromptTokens, completionTokens, current, context.maxTokens);
    }

    public TokenUsage lastTokenUsage() {
        return lastTokenUsage;
    }

    public ToolAudit audit() {
        return session.audit();
    }

    public String conversationId() {
        return session.conversationId();
    }

    public String sessionId() {
        return session.sessionId();
    }

    public boolean isPlanMode() {
        return planController.isActive();
    }

    public void enterPlanMode() {
        planController.enter();
    }

    public PlanSession planSession() {
        return planController.session();
    }

    public synchronized String createPlan(String task) throws Exception {
        return planController.createPlan(task);
    }

    public synchronized String actPlan() throws Exception {
        return actPlan(null);
    }

    public synchronized String actPlan(Consumer<String> onProgress) throws Exception {
        return planController.act(progress -> {
            recordEvent("plan_progress", Map.of("message", progress));
            if (onProgress != null) onProgress.accept(progress);
        });
    }

    public synchronized String cancelPlan() {
        return planController.cancel();
    }

    private static ExecutorService toolPool() {
        return new ThreadPoolExecutor(
                8,
                8,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public synchronized String chatFromIm(String chatId, String userInput) throws Exception {
        return im.chat(chatId, userInput, input -> chat(input, null, null));
    }

    public ImClient imClient() {
        return im.imClient();
    }

    public ScheduleStore scheduleStore() {
        return scheduleStore;
    }

    public String currentImChatId() {
        return im.currentChatId();
    }

    public void setCurrentImChatId(String chatId) {
        im.setCurrentChatId(chatId);
    }

    public void recordEvent(String kind, Map<String, Object> payload) {
        session.recordEvent(kind, payload);
    }

    // 清空对话历史
    public synchronized void reset() {
        messages.clear();
        planController.clear();
        session.resetAudit();
        lastTokenUsage = new TokenUsage(0, 0, 0, 0, context.maxTokens);
        try {
            session.resetSession();
        } catch (IOException ignored) {
        }
    }

    public synchronized void loadSession(List<Map<String, Object>> loadedMessages, String conversationId) {
        loadSession(loadedMessages, conversationId, List.of());
    }

    public synchronized void loadSession(List<Map<String, Object>> loadedMessages, String conversationId, List<Map<String, Object>> auditRecords) {
        messages.clear();
        messages.addAll(loadedMessages == null ? List.of() : loadedMessages);
        planController.clear();
        session.loadAudit(conversationId, auditRecords);
        lastTokenUsage = new TokenUsage(0, 0, 0, ContextManager.estimateTokens(messages), context.maxTokens);
    }

    public synchronized void saveSession() throws IOException {
        session.save(messages);
    }

    public synchronized String updateMemory() throws IOException {
        return session.updateMemory(messages);
    }

    // 运行一个子 Agent 来处理某个任务
    public String runSubAgent(String task, int maxRounds) throws Exception {
        return runSubAgent(task, maxRounds, null);
    }

    public String runSubAgent(String task, int maxRounds, BiConsumer<String, Map<String, Object>> onTool) throws Exception {
        return subAgents.run(task, maxRounds, onTool);
    }

    private void messagesChanged() {
        lastTokenUsage = usage(0, 0, 0, ContextManager.estimateTokens(messages));
        session.saveQuietly(messages);
    }

    private ToolExecutor.ToolObserver toolCallback(BiConsumer<String, Map<String, Object>> onTool) {
        ToolExecutor.ToolObserver observer = onTool instanceof ToolExecutor.ToolObserver toolObserver ? toolObserver : null;
        return new ToolExecutor.ToolObserver() {
            @Override
            public void accept(String name, Map<String, Object> args) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", name);
                payload.put("arguments", args == null ? Map.of() : args);
                recordEvent("tool_call", payload);
                if (onTool != null) onTool.accept(name, args);
            }

            @Override
            public void finished(String name, Map<String, Object> args, boolean success, long elapsedNanos, String result) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", name);
                payload.put("success", success);
                payload.put("elapsed_ms", Math.max(0, elapsedNanos / 1_000_000));
                payload.put("result", briefResult(result));
                recordEvent("tool_result", payload);
                if (observer != null) observer.finished(name, args, success, elapsedNanos, result);
            }
        };
    }

    private static String briefResult(String text) {
        String s = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
