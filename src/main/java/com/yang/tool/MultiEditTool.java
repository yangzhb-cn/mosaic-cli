package com.yang.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// 一个文件内多处精确替换，全部成功才写入
/** MultiEdit 工具实现，对同一文件按顺序执行多处文本替换。 */
public final class MultiEditTool extends ToolBase {
    private final ToolState state;

    public MultiEditTool() {
        this(new ToolState());
    }

    public MultiEditTool(ToolState state) {
        this.state = state == null ? new ToolState() : state;
    }

    @Override
    public String name() { return "MultiEdit"; }

    @Override
    public String description() {
        return """
                这是一个用于在一次操作中对单个文件执行多个编辑的工具。它构建在 Edit 工具之上，可高效执行多个查找替换操作。当你需要对同一文件做多处编辑时，优先使用此工具而不是 Edit 工具。

                使用此工具前：

                1. 使用 Read 工具理解文件内容和上下文
                2. 验证目录路径正确

                要进行多个文件编辑，提供以下内容：
                1. file_path：要修改文件的绝对路径（必须是绝对路径）
                2. edits：要执行的编辑操作数组，每个 edit 包含：
                   - old_string：要替换的文本（必须与文件内容完全匹配，包括所有空白和缩进）
                   - new_string：替换 old_string 的编辑后文本
                   - expected_replacements：你期望执行的替换次数。如果未指定，默认为 1。

                重要：
                - 所有编辑会按提供顺序应用
                - 每个编辑都基于上一个编辑的结果执行
                - 所有编辑都必须有效，操作才会成功；如果任何编辑失败，则不会应用任何编辑
                - 当需要修改同一文件的多个位置时，此工具很理想

                关键要求：
                1. 所有编辑都遵循单个 Edit 工具的相同要求
                2. 编辑是原子的：要么全部成功，要么全部不应用
                3. 仔细规划编辑，避免顺序操作之间冲突

                警告：
                - 如果 edits.old_string 匹配多个位置且未指定 edits.expected_replacements，工具会失败
                - 如果指定 expected_replacements 后实际匹配数量不等于该值，工具会失败
                - 如果 edits.old_string 与文件内容不完全匹配（包括空白），工具会失败
                - 如果 edits.old_string 和 edits.new_string 相同，工具会失败
                - 由于编辑会按顺序应用，确保早期编辑不会影响后续编辑要查找的文本

                编辑时：
                - 确保所有编辑结果都是符合习惯且正确的代码
                - 不要让代码处于损坏状态
                - 始终使用绝对文件路径（以 / 开头）
                """.strip();
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> edit = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "old_string", prop("string", "要替换的文本"),
                        "new_string", prop("string", "用于替换的新文本"),
                        "expected_replacements", prop("integer", "预期执行的替换次数。如果未指定，默认为 1。")
                ),
                "required", List.of("old_string", "new_string")
        );
        return params(Map.of(
                "file_path", prop("string", "要修改文件的绝对路径"),
                "edits", arrayProp("要按顺序在文件中执行的编辑操作数组", edit)
        ), "file_path", "edits");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            Path p = path(str(args, "file_path", ""));
            if (!Files.exists(p)) return "错误: 未找到文件 " + p;
            String original = Files.readString(p);
            String next = original;
            for (Map<String, Object> edit : mapList(args.get("edits"))) {
                String oldString = str(edit, "old_string", "");
                String newString = str(edit, "new_string", "");
                int expected = integer(edit, "expected_replacements", 1);
                if (oldString.equals(newString)) return "错误: old_string 和 new_string 不能相同";
                int count = count(next, oldString);
                if (count != expected) return "错误: 期望替换 " + expected + " 处，实际找到 " + count + " 处";
                next = next.replace(oldString, newString);
            }
            Files.writeString(p, next);
            state.markChanged(p);
            return "已对 " + p + " 应用 " + mapList(args.get("edits")).size() + " 处编辑";
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
}
