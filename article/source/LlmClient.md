# LlmClient.java 源码说明

## 职责

`LlmClient` 负责调用 DeepSeek/OpenAI 风格的 `/chat/completions` 流式接口，解析文本、推理内容、工具调用和 token 用量。

## 源码结构

- `ToolCall`：一次工具调用，包含 `id`、`name`、`arguments`。
- `ToolReady`：流式工具参数拼完整后通知上层。
- `Response`：一次 LLM 响应结果。
- `chat(...)`：对外请求入口，带 usage 重试保护。
- `request(...)`：构造请求、读取 SSE、解析响应。
- `PartialToolCall`：流式工具调用的临时拼接对象。
- `readyToolCall()`：判断 arguments JSON 是否完整。
- `toToolCall()`：SSE 结束后生成最终工具调用。

## 调用流程

```text
Agent.chat
-> LlmClient.chat(messages, tools, onToken, onToolReady)
-> request(..., includeUsage=true)
-> OkHttp POST /chat/completions
-> 逐行读取 SSE data
-> delta.content -> onToken
-> delta.reasoning_content -> reasoning
-> delta.tool_calls -> 按 index 拼接 PartialToolCall
-> arguments 能解析为 JSON -> onToolReady(index, ToolCall)
-> SSE 结束
-> toolMap 转 List<ToolCall>
-> 返回 Response
```

## 流式工具调用

DeepSeek/OpenAI 风格接口没有 Anthropic 的 `content_block_stop`，所以这里用“`arguments` 能成功解析成 JSON”作为工具参数完整的信号。

```text
arguments="{\"file_"
-> JSON 解析失败，继续等
arguments="{\"file_path\":\"a.txt\"}"
-> JSON 解析成功，触发 ToolReady
```

## 注意点

- 如果工具已经提前启动，请求失败时不会自动重试，避免写操作重复执行。
- 即使工具已经提前执行，`Response` 仍返回完整 `toolCalls`，供 `Agent` 写入 assistant 消息。
- `reasoning_content` 会被保留，避免 DeepSeek thinking 模式下一轮请求失败。
