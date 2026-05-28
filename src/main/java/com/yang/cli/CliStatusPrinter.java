package com.yang.cli;

import com.yang.agent.Agent;
import com.yang.tool.ToolExecutor;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/** 统一 CLI 的行式状态输出，避免各入口散落格式化逻辑。 */
final class CliStatusPrinter implements ToolExecutor.ToolObserver {
    private static final int TOOL_ARGS_WIDTH = 90;
    private final PrintStream out;
    private final StringBuilder streamed = new StringBuilder();
    private boolean assistantStarted;

    CliStatusPrinter(PrintStream out) {
        this.out = out;
    }

    void chatStarted() {
        out.println("\n🤔 思考中...");
    }

    void assistantToken(String token) {
        String text = streamed.isEmpty() ? stripLeadingBlank(token) : token;
        if (text.isEmpty()) return;
        if (!assistantStarted) {
            out.print("\n🤖 Agent: ");
            assistantStarted = true;
        }
        streamed.append(text);
        out.print(text);
    }

    void toolCall(String name, Map<String, Object> args) {
        out.println("\n🔧 Tool start: " + name + "(" + brief(args) + ")");
    }

    @Override
    public void accept(String name, Map<String, Object> args) {
        toolCall(name, args);
    }

    @Override
    public void finished(String name, Map<String, Object> args, boolean success, long elapsedNanos, String result) {
        if (success) {
            out.println("✅ Tool done: " + name + " " + elapsed(elapsedNanos) + ", " + size(result));
        } else {
            out.println("❌ Tool fail: " + name + " " + elapsed(elapsedNanos) + ", " + resultSummary(result));
        }
    }

    void planProgress(String text) {
        out.println("🧭 Plan " + text);
    }

    void chatFinished(String response, Agent.TokenUsage usage, long elapsedNanos) {
        chatFinished(assistantStarted, response, usage, elapsedNanos);
    }

    void chatFinished(boolean streamedResponse, String response, Agent.TokenUsage usage, long elapsedNanos) {
        if (streamedResponse || assistantStarted) {
            out.println();
        } else {
            out.println(agentPrefix(response));
        }
        printUsage(usage, elapsedNanos);
    }

    private void printUsage(Agent.TokenUsage usage, long elapsedNanos) {
        out.println("\n📊 Token: 总输入=" + usage.promptTokens()
                + "，缓存输入=" + usage.cachedPromptTokens()
                + "，输出=" + usage.completionTokens()
                + "，耗时=" + duration(elapsedNanos)
                + "，上下文=" + usage.contextPercent() + "% used");
    }

    private static String agentPrefix(String response) {
        String text = stripLeadingBlank(response);
        return text.contains("\n") ? "🤖 Agent:\n" + text : "🤖 Agent: " + text;
    }

    private static String stripLeadingBlank(String text) {
        return text == null ? "" : text.replaceFirst("^[\\s\\u3000]+", "");
    }

    private static String brief(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        String text = args.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("")
                .replaceAll("\\s+", " ")
                .strip();
        return text.length() > TOOL_ARGS_WIDTH ? text.substring(0, TOOL_ARGS_WIDTH) + "..." : text;
    }

    private static String duration(long elapsedNanos) {
        double seconds = Math.max(0.001, elapsedNanos / 1_000_000_000d);
        if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", seconds);
        long minutes = (long) (seconds / 60);
        return minutes + "m " + String.format(Locale.ROOT, "%.1fs", seconds % 60);
    }

    private static String elapsed(long elapsedNanos) {
        return Math.max(0, Math.round(elapsedNanos / 1_000_000d)) + "ms";
    }

    private static String size(String text) {
        int bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1024) return bytes + "B";
        return String.format(Locale.ROOT, "%.1fKB", bytes / 1024d);
    }

    private static String resultSummary(String text) {
        String summary = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        if (summary.isBlank()) return "无输出";
        return summary.length() > 120 ? summary.substring(0, 120) + "..." : summary;
    }
}
