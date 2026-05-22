package com.corecoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class Config {
    public static final String MODEL = "deepseek-v4-flash";

    public String model = MODEL;
    public String apiKey = "";
    public String baseUrl = "https://api.deepseek.com/v1";
    public int maxTokens = 4096;
    public double temperature = 0.0;
    public int maxContextTokens = 128000;

    public static Config fromEnv() {
        return from(System.getenv(), Path.of("").toAbsolutePath());
    }

    static Config from(Map<String, String> env, Path cwd) {
        Map<String, String> file = loadDotenv(cwd);
        Config c = new Config();
        c.apiKey = value(env, file, "DEEPSEEK_API_KEY", "");
        c.baseUrl = value(env, file, "DEEPSEEK_BASE_URL", c.baseUrl);
        return c;
    }

    private static String value(Map<String, String> env, Map<String, String> file, String key, String def) {
        String v = env.get(key);
        if (v != null && !v.isBlank()) return v;
        v = file.get(key);
        if (v != null && !v.isBlank()) return v;
        return def;
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
