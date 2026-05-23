# Agent.java 源码说明

## 职责

`Agent` 是核心执行循环，负责把用户输入、系统提示词、工具 schema、LLM 响应和工具结果组织成多轮对话。

## 源码结构

- `llm`：模型客户端。
- `tools`：当前 Agent 可用工具列表。
- `context`：上下文压缩器。
- `messages`：对话历史。
- `system`：系统提示词。
- `chat(...)`：主循环，执行“模型 -> 工具 -> 模型”。
- `submit(...)`：把流式就绪的工具调用提交到线程池。
- `collectResults(...)`：按 tool call 原始顺序收集工具结果。
- `runSubAgent(...)`：创建子 Agent 执行 Task 工具。
- `fullMessages()`：拼接 system message 和历史消息。
- `toolSchemas()`：把工具转换为 LLM 可识别的 schema。
- `exec(...)`：执行单个工具。

## 调用流程

```text
CliCommands.chat
-> Agent.chat(userInput, onToken, onTool)
-> messages.add(user)
-> context.maybeCompress
-> llm.chat(fullMessages, toolSchemas, text::append, onToolReady)
-> LlmClient 流式返回文本或工具调用
-> onToolReady 触发 submit，提前执行工具
-> LLM 本轮结束
-> messages.add(assistant tool_calls)
-> collectResults 按 index 收集工具结果
-> messages.add(tool result)
-> context.maybeCompress
-> 下一轮 llm.chat
```

## 注意点

- 文本 token 不直接打印给用户，而是先累积到 `text`，最终由 CLI 决定输出。
- 工具调用可以在 LLM 流式返回期间提前执行。
- 工具结果写回顺序必须和 LLM 返回的 `tool_calls` 顺序一致。
- 子 Agent 会移除 `Task` 工具，避免无限递归。
