package com.corecoder.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class EditFileTool extends ToolBase {
    @Override
    public String name() { return "Edit"; }

    @Override
    public String description() { return "通过精确字符串替换编辑文件，并校验替换次数。"; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "file_path", prop("string", "要编辑的文件路径"),
                "old_string", prop("string", "要查找的精确文本"),
                "new_string", prop("string", "用于替换的新文本"),
                "expected_replacements", prop("integer", "期望替换次数，默认 1")
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
            if (!Files.exists(p)) return "Error: " + file + " not found";
            String old = Files.readString(p);
            int count = count(old, oldString);
            if (count == 0) return "Error: old_string not found in " + file + ".\nFile starts with:\n" + old.substring(0, Math.min(500, old.length()));
            if (count != expected) return "Error: expected " + expected + " replacements but found " + count;
            String next = old.replace(oldString, newString);
            Files.writeString(p, next);
            Tools.markChanged(p);
            return "Edited " + file + "\n" + diff(old, next, p.toString());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
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
            if (out.length() > 3000) return out.substring(0, 2500) + "\n... (diff truncated)\n";
        }
        return out.toString().stripTrailing();
    }
}
