# ContextManager.java 源码说明

## 职责

`ContextManager` 负责控制对话上下文长度，避免消息历史无限增长。

## 源码结构

- `maxTokens`：上下文 token 上限基准。
- `snipAt`：50% 阈值，截断长工具输出。
- `summarizeAt`：70% 阈值，总结旧上下文。
- `collapseAt`：90% 阈值，强制折叠上下文。
- `estimateTokens(...)`：用字符数粗略估算 token。
- `maybeCompress(...)`：按阈值执行压缩。
- `snipToolOutputs(...)`：保留工具输出头尾几行。
- `summarizeOld(...)`：用 LLM 或本地提取生成摘要。
- `hardCollapse(...)`：保留最近消息，强制摘要旧消息。
- `summary(...)`：优先调用 LLM 摘要，失败则本地提取。

## 调用流程

```text
Agent.chat
-> messages.add(user)
-> context.maybeCompress(messages, llm)
-> estimateTokens
-> 超过 50% -> snipToolOutputs
-> 超过 70% -> summarizeOld
-> 超过 90% -> hardCollapse
```

工具执行后也会再次调用：

```text
tool result 写入 messages
-> context.maybeCompress
```

## 注意点

- `estimateTokens` 是本地粗略估算，不是 DeepSeek 返回的真实 usage。
- 摘要失败时不会中断主流程，会退回本地 `extract(...)`。
- 压缩直接修改传入的 `messages` 列表。
