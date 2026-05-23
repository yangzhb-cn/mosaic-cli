# CliCommands.java 源码说明

## 职责

`CliCommands` 负责终端交互循环、斜杠命令、用户输入、工具调用展示和 Agent 回复展示。

## 源码结构

- `COMMANDS`：JLine 补全候选，带中文说明。
- `repl(...)`：主交互循环。
- `terminal()`：创建 JLine 终端对象。
- `handle(...)`：处理 `/reset`、`/tokens`、`/compact`、`/diff`、`/save`、`/sessions`。
- `chat(...)`：把普通输入交给 `Agent.chat`。
- `brief(...)`：把工具参数压缩成单行摘要。

## 调用流程

```text
Main.main
-> CliCommands.repl(agent, llm, sessions)
-> LineReader.readLine("👤 你 > ")
-> /xxx -> handle
-> 普通文本 -> chat
-> Agent.chat(line, onToken, onTool)
-> onTool 打印 🔧 Tool(args)
-> onToken 打印 🤖 Agent 流式文本
```

## 斜杠命令流程

```text
/tokens
-> 读取 llm.totalPromptTokens / totalCompletionTokens
-> 打印累计 token
```

```text
/compact
-> ContextManager.estimateTokens
-> agent.context.maybeCompress
-> 再次 estimateTokens
-> 打印压缩结果
```

## 注意点

- `/` + Tab 的命令补全由 JLine `StringsCompleter` 提供。
- `terminal()` 在非交互环境下使用 dumb 模式，避免 JLine system terminal 警告。
- 工具调用显示是由 `Agent.exec` 里的 `onTool.accept(...)` 触发的。
