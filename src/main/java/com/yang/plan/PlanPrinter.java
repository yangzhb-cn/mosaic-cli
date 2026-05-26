package com.yang.plan;

import java.util.ArrayList;
import java.util.List;

/** 将执行计划和任务状态格式化为 CLI 可读表格。 */
public final class PlanPrinter {
    private static final int DESCRIPTION_WIDTH = 64;

    private PlanPrinter() {
    }

    public static String table(ExecutionPlan plan) {
        if (plan == null || plan.tasks().isEmpty()) return "📭 当前没有计划。";
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("ID", "Type", "Status", "DependsOn", "Description"));
        for (PlanTask task : plan.tasks()) {
            rows.add(new Row(
                    task.id(),
                    task.type().name(),
                    task.status().name(),
                    task.dependencies().isEmpty() ? "-" : String.join(",", task.dependencies()),
                    description(task.description())
            ));
        }
        int id = width(rows, 0);
        int type = width(rows, 1);
        int status = width(rows, 2);
        int deps = width(rows, 3);
        StringBuilder out = new StringBuilder();
        for (Row row : rows) {
            out.append(pad(row.id, id)).append("  ")
                    .append(pad(row.type, type)).append("  ")
                    .append(pad(row.status, status)).append("  ")
                    .append(pad(row.dependsOn, deps)).append("  ")
                    .append(row.description).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static int width(List<Row> rows, int column) {
        int max = 0;
        for (Row row : rows) {
            String value = switch (column) {
                case 0 -> row.id;
                case 1 -> row.type;
                case 2 -> row.status;
                case 3 -> row.dependsOn;
                default -> row.description;
            };
            max = Math.max(max, displayWidth(value));
        }
        return max;
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - displayWidth(value)));
    }

    private static String description(String text) {
        return truncate(oneLine(text), DESCRIPTION_WIDTH);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String text, int maxWidth) {
        if (displayWidth(text) <= maxWidth) return text;
        StringBuilder out = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            int w = charWidth(cp);
            if (width + w > maxWidth - 3) break;
            out.appendCodePoint(cp);
            width += w;
            i += Character.charCount(cp);
        }
        return out + "...";
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            width += charWidth(cp);
            i += Character.charCount(cp);
        }
        return width;
    }

    private static int charWidth(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return 2;
        }
        return cp > 0xFFFF ? 2 : 1;
    }

    /** 表格渲染用的行数据。 */
    private record Row(String id, String type, String status, String dependsOn, String description) {
    }
}
