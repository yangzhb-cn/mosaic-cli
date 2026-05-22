package com.corecoder;

import java.util.Arrays;
import java.util.Map;
import java.util.Scanner;

public class Main {
    public static final String VERSION = "0.1.0";

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (a.version) {
            System.out.println("core-cli " + VERSION);
            return;
        }

        Config c = Config.fromEnv();
        if (a.model != null) c.model = a.model;
        if (a.baseUrl != null) c.baseUrl = a.baseUrl;
        if (a.apiKey != null) c.apiKey = a.apiKey;
        if (c.apiKey == null || c.apiKey.isBlank()) {
            System.err.println("No API key found. Set OPENAI_API_KEY, DEEPSEEK_API_KEY, or CORECODER_API_KEY.");
            System.exit(1);
        }

        LlmClient llm = new LlmClient(c.model, c.apiKey, c.baseUrl, c.temperature, c.maxTokens);
        Agent agent = new Agent(llm, c.maxContextTokens);
        SessionStore sessions = new SessionStore();
        if (a.resume != null) {
            SessionStore.Session loaded = sessions.load(a.resume);
            if (loaded == null) {
                System.err.println("Session '" + a.resume + "' not found.");
                System.exit(1);
            }
            agent.messages.addAll(loaded.messages());
            if (a.model == null) llm.model = loaded.model();
        }

        if (a.prompt != null) {
            runOnce(agent, a.prompt);
            return;
        }
        repl(agent, llm, sessions);
    }

    private static void runOnce(Agent agent, String prompt) throws Exception {
        agent.chat(prompt, tok -> System.out.print(tok), (name, kwargs) -> System.out.println("\n> " + name + "(" + brief(kwargs) + ")"));
        System.out.println();
    }

    private static void repl(Agent agent, LlmClient llm, SessionStore sessions) throws Exception {
        System.out.println("CoreCoder v" + VERSION + "  Model: " + llm.model);
        System.out.println("Type /help for commands, quit to exit.");
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("You > ");
            if (!in.hasNextLine()) {
                break;
            }
            String line = in.nextLine().strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals("quit") || line.equals("exit") || line.equals("/quit") || line.equals("/exit")) {
                break;
            }
            if (CliCommands.handle(line, agent, llm, sessions)) {
                continue;
            }
            StringBuilder streamed = new StringBuilder();
            String response = agent.chat(line, tok -> {
                streamed.append(tok);
                System.out.print(tok);
            }, (name, kwargs) -> System.out.println("\n> " + name + "(" + brief(kwargs) + ")"));
            System.out.println(streamed.isEmpty() ? response : "");
        }
    }

    private static String brief(Map<String, Object> kwargs) {
        String s = kwargs.entrySet().stream().map(e -> e.getKey() + "=" + String.valueOf(e.getValue())).reduce((a, b) -> a + ", " + b).orElse("");
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private static class Args {
        String model;
        String baseUrl;
        String apiKey;
        String prompt;
        String resume;
        boolean version;

        static Args parse(String[] args) {
            Args out = new Args();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("-v") || a.equals("--version")) {
                    out.version = true;
                } else if ((a.equals("-m") || a.equals("--model")) && i + 1 < args.length) {
                    out.model = args[++i];
                } else if (a.equals("--base-url") && i + 1 < args.length) {
                    out.baseUrl = args[++i];
                } else if (a.equals("--api-key") && i + 1 < args.length) out.apiKey = args[++i];
                else if ((a.equals("-p") || a.equals("--prompt")) && i + 1 < args.length) {
                    out.prompt = args[++i];
                } else if ((a.equals("-r") || a.equals("--resume")) && i + 1 < args.length) {
                    out.resume = args[++i];
                } else if (a.equals("--help") || a.equals("-h")) {
                    System.out.println("Usage: core-cli [-m model] [--base-url url] [--api-key key] [-p prompt] [-r id] [-v]");
                    System.exit(0);
                } else {
                    System.err.println("Unknown argument: " + a + "\nArgs: " + Arrays.toString(args));
                    System.exit(2);
                }
            }
            return out;
        }
    }
}
