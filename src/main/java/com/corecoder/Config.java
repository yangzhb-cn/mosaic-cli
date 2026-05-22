package com.corecoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Config {
    public String model = "gpt-4o";
    public String apiKey = "";
    public String baseUrl;
    public int maxTokens = 4096;
    public double temperature = 0.0;
    public int maxContextTokens = 128000;

    public static Config fromEnv() {
        return from(System.getenv(), Path.of("").toAbsolutePath());
    }

    static Config from(Map<String, String> env, Path cwd) {
        Map<String, String> file = loadDotenv(cwd);
        Config c = new Config();
        c.model = val(env, file, "CORECODER_MODEL", "gpt-4o");
        c.apiKey = first(env, file, List.of("CORECODER_API_KEY", "OPENAI_API_KEY", "DEEPSEEK_API_KEY"), "");
        c.baseUrl = first(env, file, List.of("OPENAI_BASE_URL", "CORECODER_BASE_URL"), null);
        c.maxTokens = Integer.parseInt(val(env, file, "CORECODER_MAX_TOKENS", "4096"));
        c.temperature = Double.parseDouble(val(env, file, "CORECODER_TEMPERATURE", "0"));
        c.maxContextTokens = Integer.parseInt(val(env, file, "CORECODER_MAX_CONTEXT", "128000"));
        return c;
    }

    private static String first(Map<String, String> env, Map<String, String> file, List<String> keys, String def) {
        for (String k : keys) {
            String v = env.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        for (String k : keys) {
            String v = file.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return def;
    }

    private static String val(Map<String, String> env, Map<String, String> file, String key, String def) {
        return first(env, file, List.of(key), def);
    }

    private static Map<String, String> loadDotenv(Path cwd) {
        Path cur = cwd.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        while (cur != null) {
            Path p = cur.resolve(".env");
            if (Files.exists(p)) return parseDotenv(p);
            if (cur.equals(home) || cur.equals(cur.getParent())) break;
            cur = cur.getParent();
        }
        return Map.of();
    }

    private static Map<String, String> parseDotenv(Path p) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(p)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) continue;
                int i = s.indexOf('=');
                String key = s.substring(0, i).trim();
                String value = s.substring(i + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(key, value);
            }
        } catch (IOException ignored) {
        }
        return out;
    }
}
