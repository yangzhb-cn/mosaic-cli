package com.corecoder.cli;

/**
 * MisaicCoder 启动 Banner
 */
public final class CliBanner {
    private static final String RESET = "\u001B[0m";
    private static final String ACCENT = "\u001B[38;2;0;72;160m";
    private static final String TITLE = "\u001B[1m\u001B[38;2;0;36;80m";
    private static final String MUTED = "\u001B[38;2;88;96;112m";
    private static final int MIN_RULE_WIDTH = 42;

    private CliBanner() {
    }

    // 打印Banner
    public static void print(String version, String model) {
        String meta = "🔞" + version + "  模型: " + model;
        int width = Math.max(MIN_RULE_WIDTH, visualWidth(meta) + 3);
        if (isColorEnabled()) {
            printColorBanner(meta, width);
        } else {
            printPlainBanner(meta, width);
        }
    }

    private static void printColorBanner(String meta, int width) {
        System.out.println();
        System.out.println(ACCENT + "╭─ " + TITLE + "MisaicCoder CLI" + RESET);
        System.out.println(ACCENT + "│  " + MUTED + meta + RESET);
        System.out.println(ACCENT + "╰" + "─".repeat(width) + RESET);
        System.out.println();
    }

    private static void printPlainBanner(String meta, int width) {
        System.out.println();
        System.out.println("╭─ MisaicCoder CLI");
        System.out.println("│  " + meta);
        System.out.println("╰" + "─".repeat(width));
        System.out.println();
    }

    private static int visualWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            width += script == Character.UnicodeScript.HAN ? 2 : 1;
        }
        return width;
    }

    private static boolean isColorEnabled() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        String term = System.getenv("TERM");
        return term == null || !term.equalsIgnoreCase("dumb");
    }
}
