package com.yang.cli;

import com.yang.session.SessionManager;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliCommandCompleterTest {
    @TempDir
    Path temp;

    @Test
    void slashCompletionIncludesGroupsAndDescriptions() throws Exception {
        List<Candidate> candidates = complete("/", new SessionManager(temp.resolve("data")));

        Candidate session = candidate(candidates, "/session");
        assertEquals("Session", session.group());
        assertEquals("查看当前会话用户消息", session.descr());
        assertTrue(candidates.stream().anyMatch(c -> "Plan".equals(c.group()) && "/plan".equals(c.value())));
        assertTrue(candidates.stream().anyMatch(c -> "System".equals(c.group()) && "/mcp".equals(c.value())));
    }

    @Test
    void commandSubcompletionSuggestsOnlyValidSubcommands() throws Exception {
        List<Candidate> audit = complete("/audit ", new SessionManager(temp.resolve("data")));
        assertEquals(List.of("save"), values(audit));

        List<Candidate> memory = complete("/memory ", new SessionManager(temp.resolve("data")));
        assertEquals(List.of("update"), values(memory));

        List<Candidate> session = complete("/session s", new SessionManager(temp.resolve("data")));
        assertEquals(List.of("switch"), values(session));
    }

    @Test
    void sessionSwitchCompletesExistingSessionIds() throws Exception {
        SessionManager sessions = new SessionManager(temp.resolve("data"));
        sessions.create("alpha", "test-model");
        sessions.create("beta", "test-model");

        List<Candidate> candidates = complete("/session switch a", sessions);

        assertEquals(List.of("alpha"), values(candidates));
        assertEquals("Session", candidates.getFirst().group());
        assertEquals("切换到已有会话", candidates.getFirst().descr());
    }

    private static List<Candidate> complete(String buffer, SessionManager sessions) throws Exception {
        Completer completer = new CliCommandCompleter(sessions);
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, new DefaultParser().parse(buffer, buffer.length()), candidates);
        return candidates;
    }

    private static Candidate candidate(List<Candidate> candidates, String value) {
        return candidates.stream()
                .filter(c -> value.equals(c.value()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> values(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::value).toList();
    }
}
