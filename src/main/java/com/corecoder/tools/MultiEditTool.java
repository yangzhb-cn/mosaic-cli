package com.corecoder.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class MultiEditTool extends ToolBase {
    @Override
    public String name() { return "MultiEdit"; }

    @Override
    public String description() { return "对单个文件按顺序执行多个精确字符串替换，全部成功才写入。"; }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> edit = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "old_string", prop("string", "要替换的文本"),
                        "new_string", prop("string", "用于替换的新文本"),
                        "expected_replacements", prop("integer", "期望替换次数，默认 1")
                ),
                "required", List.of("old_string", "new_string")
        );
        return params(Map.of(
                "file_path", prop("string", "要编辑的文件绝对路径"),
                "edits", arrayProp("按顺序应用的编辑列表", edit)
        ), "file_path", "edits");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            Path p = path(str(args, "file_path", ""));
            if (!Files.exists(p)) return "Error: " + p + " not found";
            String original = Files.readString(p);
            String next = original;
            for (Map<String, Object> edit : mapList(args.get("edits"))) {
                String oldString = str(edit, "old_string", "");
                String newString = str(edit, "new_string", "");
                int expected = integer(edit, "expected_replacements", 1);
                if (oldString.equals(newString)) return "Error: old_string and new_string must differ";
                int count = count(next, oldString);
                if (count != expected) return "Error: expected " + expected + " replacements but found " + count;
                next = next.replace(oldString, newString);
            }
            Files.writeString(p, next);
            Tools.markChanged(p);
            return "Applied " + mapList(args.get("edits")).size() + " edits to " + p;
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
}
