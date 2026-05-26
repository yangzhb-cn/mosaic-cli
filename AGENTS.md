# AGENTS.md

## Project

This is a Java 21 + Maven CLI agent project named Mosaic CLI Java. The shaded jar entrypoint is `com.yang.Main`, configured in `pom.xml`. Source and test packages use `com.yang`; do not reintroduce `com.coder` paths or package names.

## Working Rules

- Keep changes MVP-sized and simple.
- Do not add code when a README, prompt, config, or test-only change solves the request.
- Touch only files required by the task. Do not refactor adjacent code or reformat unrelated lines.
- Match the current Java style: small classes, direct control flow, package-local helpers where possible.
- Avoid speculative abstractions, new configuration knobs, or future-proofing unless the user explicitly asks.
- Preserve user work in the current tree. Do not revert unrelated changes.

## Common Commands

```bash
mvn test
mvn -Dtest=CliCommandsTest test
mvn -DskipTests package
java -jar target/core-cli-0.1.0.jar
```

Use focused Maven tests while iterating, then run `mvn test` when the change can affect shared behavior.

## Architecture Map

- `src/main/java/com/yang/Main.java`: startup, config, MCP, skills, Telegram, REPL wiring.
- `src/main/java/com/yang/Agent.java`: message history, model/tool loop, context compression, sub-agent dispatch.
- `src/main/java/com/yang/LlmClient.java`: OpenAI-compatible streaming chat client and tool call parsing.
- `src/main/java/com/yang/SessionStore.java`: session save/list/load persistence under `~/.mosaiccoder/sessions`.
- `src/main/java/com/yang/cli/CliCommands.java`: slash commands and terminal rendering.
- `src/main/java/com/yang/tool/`: built-in tool definitions and `ToolExecutor`.
- `src/main/java/com/yang/audit/`: tool audit stats and JSONL snapshot persistence.
- `src/main/java/com/yang/mcp/`: MCP config loading and MCP tool wrapping.
- `src/main/java/com/yang/skill/`: local skill discovery and parsing.
- `src/main/resources/com/yang/prompt/`: system and compression prompt resources.

## Implementation Guidance

- For CLI commands, add the command to `CliCommands.COMMANDS`, handle it in `CliCommands.handle(...)`, and cover it in `src/test/java/com/yang/cli/CliCommandsTest.java`.
- For tool behavior, prefer changing `ToolExecutor` or the specific tool class instead of adding a new layer.
- For session behavior, keep persistence in `SessionStore` and runtime restoration in `Agent`.
- For audit behavior, keep statistics and JSONL formatting in `com.yang.audit`; other packages should only call its public methods.
- For prompt behavior, keep prompt resources under `src/main/resources/com/yang/prompt/` in sync with `com.yang.prompt.Prompt`.
- For user-visible behavior changes, update `README.md`.

## Testing Expectations

- Add or update focused tests for changed behavior.
- Use temp directories in tests for filesystem writes.
- Do not require real API keys, network access, Telegram, Tavily, or MCP servers in tests.
- If full `mvn test` fails because of a pre-existing unrelated test, report the exact failing test and still run the focused tests for your change.
