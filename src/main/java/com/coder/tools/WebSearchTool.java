package com.coder.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// 使用 Tavily Search API 搜索网页，返回可引用的链接和摘要
public final class WebSearchTool extends ToolBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final Map<String, String> env;
    private final Path cwd;

    public WebSearchTool() {
        this(System.getenv(), Path.of("").toAbsolutePath());
    }

    public WebSearchTool(Map<String, String> env, Path cwd) {
        this.env = env;
        this.cwd = cwd;
    }

    @Override
    public String name() { return "WebSearch"; }

    @Override
    public String description() {
        return """
                使用 Tavily 搜索网页并返回最新信息来源。适用于查找文档、事实、新闻、API 参考或当前信息。

                使用：
                - query 是搜索查询
                - allowed_domains 可限制只返回指定域名
                - blocked_domains 可排除指定域名
                - 需要设置 TAVILY_API_KEY 环境变量，或在当前目录/父目录的 .env 中配置
                - 返回结果包含 answer、标题、URL、摘要和 score；回答用户时应列出相关 Sources
                - 如果已经有具体 URL，直接使用 WebFetch 读取页面更合适
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "query", prop("string", "要搜索的查询"),
                "allowed_domains", arrayProp("仅包含这些 domains 的搜索结果", Map.of("type", "string")),
                "blocked_domains", arrayProp("排除这些 domains 的搜索结果", Map.of("type", "string"))
        ), "query");
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = str(args, "query", "").trim();
        if (query.isBlank()) return "错误: query 不能为空";

        List<String> allowed = list(args.get("allowed_domains"));
        List<String> blocked = list(args.get("blocked_domains"));
        if (!allowed.isEmpty() && !blocked.isEmpty()) return "错误: allowed_domains 和 blocked_domains 不能同时使用";

        String key = apiKey();
        if (key.isBlank()) return "错误: 未找到 TAVILY_API_KEY。请设置环境变量或在当前目录/父目录的 .env 中添加 TAVILY_API_KEY。";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("search_depth", "basic");
        body.put("max_results", 5);
        body.put("include_answer", true);
        if (!allowed.isEmpty()) body.put("include_domains", allowed);
        if (!blocked.isEmpty()) body.put("exclude_domains", blocked);

        try {
            Request req = new Request.Builder()
                    .url("https://api.tavily.com/search")
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(RequestBody.create(JSON.writeValueAsString(body), JSON_TYPE))
                    .build();
            try (Response res = http.newCall(req).execute()) {
                String text = res.body() == null ? "" : res.body().string();
                if (!res.isSuccessful()) return "错误: Tavily HTTP " + res.code() + ": " + truncate(text, 1000);
                return formatResult(query, JSON.readTree(text));
            }
        } catch (Exception e) {
            return "错误: Tavily 搜索失败: " + e.getMessage();
        }
    }

    private String formatResult(String query, JsonNode root) {
        StringBuilder out = new StringBuilder("Web search results for query: \"").append(query).append("\"\n\n");
        String answer = root.path("answer").asText("");
        if (!answer.isBlank()) out.append("Answer: ").append(answer.strip()).append("\n\n");

        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) return out.append("未找到搜索结果。").toString();
        for (int i = 0; i < results.size(); i++) {
            JsonNode r = results.get(i);
            String title = r.path("title").asText(r.path("url").asText("Untitled"));
            String url = r.path("url").asText("");
            String content = r.path("content").asText("");
            out.append(i + 1).append(". [").append(title).append("](").append(url).append(")\n");
            if (!content.isBlank()) out.append("   ").append(content.strip()).append("\n");
            if (r.has("score")) out.append("   score: ").append(r.path("score").asDouble()).append("\n");
            out.append("\n");
        }
        out.append("REMINDER: 使用这些结果回答用户时，请用 markdown 链接列出相关来源。");
        return out.toString().stripTrailing();
    }

    private String apiKey() {
        String v = env.get("TAVILY_API_KEY");
        if (v != null && !v.isBlank()) return v.trim();

        Path cur = cwd.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        while (cur != null) {
            Path p = cur.resolve(".env");
            String fromFile = dotenvValue(p);
            if (!fromFile.isBlank()) return fromFile;
            if (cur.equals(home) || cur.equals(cur.getParent())) break;
            cur = cur.getParent();
        }
        return "";
    }

    private String dotenvValue(Path p) {
        if (!Files.exists(p)) return "";
        try {
            for (String line : Files.readAllLines(p)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) continue;
                int i = s.indexOf('=');
                if (!s.substring(0, i).trim().equals("TAVILY_API_KEY")) continue;
                String value = s.substring(i + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max) + "\n... (truncated at " + max + " chars)";
    }
}
