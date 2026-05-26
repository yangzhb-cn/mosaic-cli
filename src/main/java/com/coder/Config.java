package com.coder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*  提供默认配置
* 从环境变量和 .env 文件加载运行参数
* 统一输出一个 Config 对象给程序其他部分使用
*/
public class Config {
    public static final String MODEL = "deepseek-v4-flash";

    public String model = MODEL;
    public String apiKey = "";
    public String baseUrl = "https://api.deepseek.com/v1";
    public double temperature = 0.0;
    public int maxContextTokens = 128000;
    
    public String im = "";
    public String telegramBotToken = "";
    public String telegramOwnerId = "";

    public static Config fromEnv() {
        // 读取系统环境变量 System.getenv()
        // 以当前工作目录作为起点去找 .env 文件
        return from(System.getenv(), Path.of("").toAbsolutePath());
    }

    // 环境变量 > .env 文件 > 默认值
    static Config from(Map<String, String> env, Path cwd) {
        Map<String, String> file = loadDotenv(cwd);
        Config c = new Config();
        c.model = value(env, file, "DEEPSEEK_MODEL", c.model);
        c.apiKey = value(env, file, "DEEPSEEK_API_KEY", c.apiKey);
        c.baseUrl = value(env, file, "DEEPSEEK_BASE_URL", c.baseUrl);
        c.im = value(env, file, "MISAIC_IM", c.im);
        c.telegramBotToken = value(env, file, "TELEGRAM_BOT_TOKEN", c.telegramBotToken);
        c.telegramOwnerId = first(env, file, List.of("TELEGRAM_OWNER_ID", "OWNER_ID"), c.telegramOwnerId);
        if (c.im.isBlank() && !c.telegramBotToken.isBlank() && !c.telegramOwnerId.isBlank()) c.im = "telegram";
        return c;
    }

    public boolean telegramEnabled() {
        return "telegram".equalsIgnoreCase(im) && !telegramBotToken.isBlank() && !telegramOwnerId.isBlank();
    }

    // 取值优先级
    private static String value(Map<String, String> env, Map<String, String> file, String key, String def) {
        String v = env.get(key);
        if (v != null && !v.isBlank()) return v;
        v = file.get(key);
        if (v != null && !v.isBlank()) return v;
        return def;
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

    // 从当前目录开始向上查找 .env 文件: 可以把 .env 放在项目根目录，程序从子目录启动时也能找到
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

    // 简化版的 .env 解析器
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
