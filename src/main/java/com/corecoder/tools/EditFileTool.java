package com.corecoder.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class EditFileTool extends ToolBase {
    @Override
    public String name() { return "edit_file"; }

    @Override
    public String description() { return "Edit a file by replacing an exact unique string match."; }

    @Override
    public Map<String, Object> parameters() {
        return params(Map.of(
                "file_path", prop("string", "Path to the file to edit"),
                "old_string", prop("string", "Exact text to find"),
                "new_string", prop("string", "Replacement text")
        ), "file_path", "old_string", "new_string");
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String file = str(args, "file_path", "");
            String oldString = str(args, "old_string", "");
            String newString = str(args, "new_string", "");
            Path p = path(file);
            if (!Files.exists(p)) return "Error: " + file + " not found";
            String old = Files.readString(p);
            int count = count(old, oldString);
            if (count == 0) return "Error: old_string not found in " + file + ".\nFile starts with:\n" + old.substring(0, Math.min(500, old.length()));
            if (count > 1) return "Error: old_string appears " + count + " times in " + file + ". Include more surrounding lines to make it unique.";
            int at = old.indexOf(oldString);
            String next = old.substring(0, at) + newString + old.substring(at + oldString.length());
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
