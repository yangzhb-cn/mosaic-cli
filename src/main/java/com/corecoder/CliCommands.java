package com.corecoder;


import com.corecoder.tools.Tools;

public final class CliCommands {
    private static final String HELP = """
            Commands:
              /help          Show this help
              /reset         Clear conversation history
              /model         Show current model
              /model <name>  Switch model
              /tokens        Show token usage
              /compact       Compress conversation context
              /diff          Show files modified this session
              /save          Save session
              /sessions      List saved sessions
              quit           Exit
            """;

    private CliCommands() {
    }

    public static boolean handle(String line, Agent agent, LlmClient llm, SessionStore sessions) throws Exception {
        if (line.equals("/help")) {
            System.out.println(HELP);
            return true;
        }
        if (line.equals("/reset")) {
            agent.reset();
            System.out.println("Conversation reset.");
            return true;
        }
        if (line.equals("/tokens")) {
            String s = "Tokens: " + llm.totalPromptTokens + " prompt + " + llm.totalCompletionTokens + " completion = " + (llm.totalPromptTokens + llm.totalCompletionTokens);
            Double cost = llm.estimatedCost();
            System.out.println(cost == null ? s : s + "  (~$" + String.format("%.4f", cost) + ")");
            return true;
        }
        if (line.equals("/model") || line.startsWith("/model ")) {
            String m = line.length() > 7 ? line.substring(7).strip() : "";
            if (m.isEmpty()) System.out.println("Current model: " + llm.model);
            else {
                llm.model = m;
                System.out.println("Switched to " + m);
            }
            return true;
        }
        if (line.equals("/compact")) {
            int before = ContextManager.estimateTokens(agent.messages);
            boolean changed = agent.context.maybeCompress(agent.messages, llm);
            int after = ContextManager.estimateTokens(agent.messages);
            System.out.println(changed ? "Compressed: " + before + " -> " + after + " tokens" : "Nothing to compress (" + before + " tokens)");
            return true;
        }
        if (line.equals("/diff")) {
            if (Tools.changedFiles().isEmpty()) System.out.println("No files modified this session.");
            else Tools.changedFiles().stream().sorted().forEach(f -> System.out.println("  " + f));
            return true;
        }
        if (line.equals("/save")) {
            String id = sessions.save(agent.messages, llm.model, null);
            System.out.println("Session saved: " + id);
            return true;
        }
        if (line.equals("/sessions")) {
            for (SessionStore.SessionInfo s : sessions.list()) System.out.println("  " + s.id() + " (" + s.model() + ", " + s.savedAt() + ") " + s.preview());
            return true;
        }
        return false;
    }
}
