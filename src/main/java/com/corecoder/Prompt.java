package com.corecoder;

import com.corecoder.tools.Tools;

import java.nio.file.Path;
import java.util.List;

public class Prompt {
    public static String systemPrompt(List<Tools.Tool> tools) {
        StringBuilder s = new StringBuilder();
        s.append("You are CoreCoder, a minimal terminal coding agent.\n");
        s.append("Working directory: ").append(Path.of("").toAbsolutePath()).append('\n');
        s.append("OS: ").append(System.getProperty("os.name")).append('\n');
        s.append("Use tools to inspect and edit files. Keep changes small and explain results.\n\n");
        s.append("Available tools:\n");
        for (Tools.Tool t : tools) {
            s.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
        }
        return s.toString();
    }
}
