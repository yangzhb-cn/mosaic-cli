package com.yang.cli;

import com.yang.session.SessionManager;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/** 补全斜杠命令、子命令和可切换的 session id。 */
final class CliCommandCompleter implements Completer {
    private final SessionManager sessions;

    CliCommandCompleter(SessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line == null ? "" : line.line();
        int cursor = line == null ? buffer.length() : Math.min(line.cursor(), buffer.length());
        String before = buffer.substring(0, cursor);
        if (!before.startsWith("/")) return;

        if (before.startsWith("/session switch ")) {
            completeSessions(before.substring("/session switch ".length()), candidates);
            return;
        }
        if (before.startsWith("/session ")) {
            completeWords(before.substring("/session ".length()), "Session", "会话命令", List.of("list", "new", "switch"), candidates);
            return;
        }
        if (before.startsWith("/audit ")) {
            completeWords(before.substring("/audit ".length()), "System", "保存当前对话工具调用统计", List.of("save"), candidates);
            return;
        }
        if (before.startsWith("/memory ")) {
            completeWords(before.substring("/memory ".length()), "System", "提炼当前会话并更新长期记忆", List.of("update"), candidates);
            return;
        }

        for (CliRouter.CommandSpec spec : CliRouter.commandSpecs()) {
            if (spec.command().startsWith(before)) {
                candidates.add(new Candidate(spec.command(), spec.command(), spec.group(), spec.description(), null, null, true));
            }
        }
    }

    private void completeSessions(String prefix, List<Candidate> candidates) {
        if (sessions == null) return;
        try {
            for (SessionManager.SessionInfo session : sessions.list()) {
                if (session.id().startsWith(prefix)) {
                    candidates.add(new Candidate(session.id(), session.id(), "Session", "切换到已有会话", null, null, true));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void completeWords(String prefix, String group, String description, List<String> words, List<Candidate> candidates) {
        for (String word : words) {
            if (word.startsWith(prefix)) {
                candidates.add(new Candidate(word, word, group, description, null, null, true));
            }
        }
    }
}
