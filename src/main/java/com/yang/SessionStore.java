package com.yang;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 负责把当前对话保存为本地 JSON 文件，并从本地 JSON 文件恢复会话。
 */
public class SessionStore {
    /** 项目统一使用的 JSON 序列化器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 会话 ID 清洗规则：非安全文件名字符会被替换。 */
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");

    /** 会话文件所在目录。 */
    private final Path dir;

    /** 加载出来的完整会话内容。 */
    public record Session(List<Map<String, Object>> messages, String model, String conversationId) {}

    /** 会话列表页展示用的轻量信息。 */
    public record SessionInfo(String id, String model, String savedAt, String preview) {}

    /**
     * 使用默认目录 ~/.corecoder/sessions 保存会话。
     */
    public SessionStore() {
        // 默认把会话放到用户目录下的隐藏目录，避免污染项目文件。
        this(Path.of(System.getProperty("user.home"), ".mosaiccoder", "sessions"));
    }

    /**
     * 使用指定目录保存会话，主要方便测试或自定义路径。
     */
    public SessionStore(Path dir) {
        // 保存会话目录，实际写入时再创建。
        this.dir = dir;
    }

    /**
     * 保存当前消息列表，并返回最终使用的会话 ID。
     */
    public String save(List<Map<String, Object>> messages, String model, String id) throws IOException {
        return save(messages, model, id, null);
    }

    public String save(List<Map<String, Object>> messages, String model, String id, String conversationId) throws IOException {
        // 确保会话目录存在。
        Files.createDirectories(dir);

        // 清洗或生成会话 ID，避免非法文件名。
        String sid = normalize(id);

        // 组装写入 JSON 的会话数据。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", sid);
        data.put("model", model);
        data.put("saved_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        data.put("messages", messages);
        if (conversationId != null && !conversationId.isBlank()) data.put("conversation_id", conversationId);

        // 以格式化 JSON 写入对应会话文件。
        JSON.writerWithDefaultPrettyPrinter().writeValue(path(sid).toFile(), data);

        // 返回真实保存使用的会话 ID。
        return sid;
    }

    /**
     * 按会话 ID 加载保存过的消息和模型名称。
     */
    public Session load(String id) throws IOException {
        // 根据 ID 计算安全的 JSON 文件路径。
        Path p = path(id);

        // 文件不存在时返回 null，交给调用方决定提示方式。
        if (!Files.exists(p)) {
            return null;
        }

        // 读取 JSON 文件为 Map。
        Map<String, Object> data = JSON.readValue(p.toFile(), new TypeReference<>() {});

        // Jackson 反序列化泛型 Map 需要显式转型。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) data.get("messages");

        // 返回会话消息和当时使用的模型。
        Object conversationId = data.get("conversation_id");
        return new Session(messages, String.valueOf(data.get("model")), conversationId == null ? null : String.valueOf(conversationId));
    }

    /**
     * 列出最近最多 20 个会话文件的信息。
     */
    public List<SessionInfo> list() throws IOException {
        // 目录还不存在时表示没有保存过会话。
        if (!Files.exists(dir)) {
            return List.of();
        }

        // 打开会话目录流，方法结束时自动关闭。
        try (var stream = Files.list(dir)) {
            // 只处理 JSON 会话文件。
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .limit(20)
                    .map(this::info)
                    .filter(i -> i != null)
                    .toList();
        }
    }

    /**
     * 从单个会话文件中提取列表展示信息。
     */
    private SessionInfo info(Path p) {
        // 单个坏文件不应影响整个会话列表。
        try {
            // 读取会话 JSON。
            Map<String, Object> data = JSON.readValue(p.toFile(), new TypeReference<>() {});

            // 默认预览为空。
            String preview = "";

            // 取出原始 messages 字段。
            Object raw = data.get("messages");

            // 只有 messages 确实是列表时才尝试提取预览。
            if (raw instanceof List<?> list) {
                // 从前往后找第一条用户消息。
                for (Object item : list) {
                    // 只使用 role=user 且 content 非空的消息作为预览。
                    if (item instanceof Map<?, ?> m && "user".equals(m.get("role")) && m.get("content") != null) {
                        // 转成字符串，兼容非 String 类型内容。
                        preview = String.valueOf(m.get("content"));

                        // 预览最多保留 80 个字符。
                        if (preview.length() > 80) {
                            preview = preview.substring(0, 80);
                        }

                        // 找到第一条用户消息后就停止。
                        break;
                    }
                }
            }

            // 返回列表展示需要的会话信息。
            return new SessionInfo(
                    String.valueOf(data.getOrDefault("id", p.getFileName().toString().replaceFirst("\\.json$", ""))),
                    String.valueOf(data.getOrDefault("model", "?")),
                    String.valueOf(data.getOrDefault("saved_at", "?")),
                    preview
            );
        } catch (IOException e) {
            // 读取失败时丢弃该文件，避免列表命令整体失败。
            return null;
        }
    }

    /**
     * 根据会话 ID 生成安全的 JSON 文件路径。
     */
    private Path path(String id) {
        // 使用 normalize 后的 ID 拼出绝对路径。
        Path p = dir.resolve(normalize(id) + ".json").toAbsolutePath().normalize();

        // 计算会话目录的绝对规范路径。
        Path root = dir.toAbsolutePath().normalize();

        // 防止通过 ../ 等方式逃逸会话目录。
        if (!p.getParent().equals(root)) {
            throw new IllegalArgumentException("会话 ID 无效");
        }

        // 返回安全路径。
        return p;
    }

    /**
     * 清洗用户传入的会话 ID；为空时生成新的唯一 ID。
     */
    private static String normalize(String id) {
        // 空 ID 自动生成 session_时间戳_随机后缀。
        if (id == null || id.isBlank()) {
            return "session_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        }

        // 去掉首尾空白，并把 Windows 路径分隔符统一成 /。
        String s = id.trim().replace('\\', '/');

        // 如果用户传了路径，只取最后一段作为文件名。
        s = s.substring(s.lastIndexOf('/') + 1);

        // 把不安全字符替换成 -，再去掉开头结尾的点、下划线和横线。
        s = UNSAFE.matcher(s).replaceAll("-").replaceAll("^[._-]+|[._-]+$", "");

        // 清洗后为空则重新生成 ID，否则返回清洗后的 ID。
        return s.isBlank() ? normalize(null) : s;
    }
}
