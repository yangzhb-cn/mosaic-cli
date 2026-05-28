package com.yang.cli;

import com.yang.agent.Agent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliStatusPrinterTest {
    @Test
    void printsChatEventsInStableOrder() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliStatusPrinter printer = new CliStatusPrinter(new PrintStream(out, true, StandardCharsets.UTF_8));

        printer.chatStarted();
        printer.assistantToken(" hello");
        printer.toolCall("Read", Map.of("path", "/tmp/example", "content", "line one\nline two"));
        printer.chatFinished(false, "fallback", new Agent.TokenUsage(10, 2, 3, 50, 100), 1_500_000_000L);

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.indexOf("思考中") < text.indexOf("Agent"));
        assertTrue(text.indexOf("Agent") < text.indexOf("Tool start"));
        assertTrue(text.indexOf("Tool start") < text.indexOf("Token"));
        assertTrue(text.contains("Read("));
        assertTrue(text.contains("line one line two"));
    }

    @Test
    void compressesLongToolArguments() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliStatusPrinter printer = new CliStatusPrinter(new PrintStream(out, true, StandardCharsets.UTF_8));

        printer.toolCall("Write", Map.of("content", "x".repeat(160)));

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("..."));
        assertTrue(text.length() < 150);
    }

    @Test
    void formatsPlanProgressThroughSameStatusPrinter() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliStatusPrinter printer = new CliStatusPrinter(new PrintStream(out, true, StandardCharsets.UTF_8));

        printer.planProgress("▶ T1 FILE_READ read files");

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Plan ▶ T1 FILE_READ read files"));
    }

    @Test
    void printsToolDoneAndFailResults() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliStatusPrinter printer = new CliStatusPrinter(new PrintStream(out, true, StandardCharsets.UTF_8));

        printer.finished("Read", Map.of("path", "a.txt"), true, 42_000_000L, "hello");
        printer.finished("Bash", Map.of("command", "bad"), false, 300_000_000L, "错误: command failed\nwith details");

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("✅ Tool done: Read 42ms, 5B"));
        assertTrue(text.contains("❌ Tool fail: Bash 300ms, 错误: command failed with details"));
    }
}
