package com.yang.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// 单点精确字符串替换，校验替换次数
/** Edit 工具实现，对文件中的唯一文本片段做单次替换。 */
public final class EditFileTool extends ToolBase {
    @Override
    public String name() { return "Edit"; }

    @Override
    public String description() {
        return """
                对文件执行严格出现次数校验的精确字符串替换。

                使用：
                - 编辑 Read 工具输出中的文本时，确保保留行号前缀之后显示的精确缩进（tabs/spaces）。行号前缀格式为：行号 + tab。该 tab 之后的所有内容才是真正的文件内容。绝不要把行号前缀的任何部分包含在 old_string 或 new_string 中。
                - 始终优先编辑代码库中的已有文件。除非明确需要，绝不要写新文件。
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "file_path", prop("string", "要修改文件的绝对路径"),
                "old_string", prop("string", "要替换的文本"),
                "new_string", prop("string", "用于替换的新文本（必须不同于 old_string）"),
                "expected_replacements", prop("integer", "预期执行的替换次数。如果未指定，默认为 1。")
        ), "file_path", "old_string", "new_string");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String file = str(args, "file_path", "");
            String oldString = str(args, "old_string", "");
            String newString = str(args, "new_string", "");
            int expected = integer(args, "expected_replacements", 1);
            Path p = path(file);
            if (!Files.exists(p)) return "错误: 未找到文件 " + file;
            String old = Files.readString(p);
            int count = count(old, oldString);
            if (count == 0) return "错误: old_string 未在 " + file + " 中找到。\n文件开头:\n" + old.substring(0, Math.min(500, old.length()));
            if (count != expected) return "错误: 期望替换 " + expected + " 处，实际找到 " + count + " 处";
            String next = old.replace(oldString, newString);
            Files.writeString(p, next);
            Tools.markChanged(p);
            return "已编辑 " + file + "\n" + diff(old, next, p.toString());
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }

    private int count(String text, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private String diff(String old, String next, String file) {
        List<String> a = old.lines().toList();
        List<String> b = next.lines().toList();
        StringBuilder out = new StringBuilder("--- a/").append(file).append('\n').append("+++ b/").append(file).append('\n').append("@@\n");
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            String left = i < a.size() ? a.get(i) : null;
            String right = i < b.size() ? b.get(i) : null;
            if (left != null && left.equals(right)) out.append(' ').append(left).append('\n');
            else {
                if (left != null) out.append('-').append(left).append('\n');
                if (right != null) out.append('+').append(right).append('\n');
            }
            if (out.length() > 3000) return out.substring(0, 2500) + "\n... (diff 已截断)\n";
        }
        return out.toString().stripTrailing();
    }
}
