package com.yang.tool;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Map;
import java.util.concurrent.TimeUnit;

// 抓取指定 URL 的网页内容，返回简化后的文本片段
/** WebFetch 工具实现，抓取指定 URL 的正文内容。 */
public final class WebFetchTool extends ToolBase {
    private static final int MAX_CHARS = 10000;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    @Override
    public String name() { return "WebFetch"; }

    @Override
    public String description() {
        return """
                获取指定 URL 的网页内容，返回简化后的文本片段。适用于读取具体网页、文档、文章或 WebSearch 返回的链接。

                使用：
                - url 必须是完整 URL
                - prompt 可用于说明你希望从页面中关注什么，但工具只负责抓取和简化文本，不会在工具内部调用模型总结或提取
                - HTML 会被简单转换为纯文本；长内容会被截断
                - 此工具只读，不修改任何文件
                - 如果需要先发现网页或查找最新资料，先使用 WebSearch
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "url", prop("string", "要获取内容的 URL"),
                "prompt", prop("string", "希望从页面内容中关注或提取的信息")
        ), "url");
    }

    @Override
    public String execute(Map<String, Object> args) {
        String url = str(args, "url", "").trim();
        if (url.isBlank()) return "错误: url 不能为空";

        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "CoreCoder/0.1")
                .header("Accept", "text/html,text/plain,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build();
        try (Response res = http.newCall(req).execute()) {
            String body = res.body() == null ? "" : res.body().string();
            if (!res.isSuccessful()) return "错误: HTTP " + res.code() + " for " + url + "\n" + truncate(body, 1000);

            String contentType = res.header("Content-Type", "");
            String text = looksLikeHtml(contentType, body) ? htmlToText(body) : body.strip();
            return "URL: " + res.request().url() + "\nHTTP: " + res.code() + "\n\n" + truncate(text, MAX_CHARS);
        } catch (Exception e) {
            return "错误: 获取 URL 失败: " + e.getMessage();
        }
    }

    private boolean looksLikeHtml(String contentType, String body) {
        return contentType.toLowerCase().contains("html") || body.stripLeading().startsWith("<");
    }

    private String htmlToText(String html) {
        return decodeEntities(html)
                .replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript\\b[^>]*>.*?</noscript>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|li|tr)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s+", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private String decodeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max) + "\n... (truncated at " + max + " chars)";
    }
}
