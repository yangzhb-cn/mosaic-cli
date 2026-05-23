# SessionStore.java 源码说明

## 职责

`SessionStore` 负责保存、加载和列出对话会话。

## 源码结构

- `Session`：加载出的完整会话数据。
- `SessionInfo`：列表展示用的会话摘要。
- `dir`：会话目录，默认 `~/.corecoder/sessions`。
- `save(...)`：保存 messages、model、时间和 id。
- `load(...)`：按 id 加载会话 JSON。
- `list()`：列出最近 20 个会话文件。
- `info(...)`：从会话文件提取展示信息。
- `path(...)`：构造并校验会话文件路径。
- `normalize(...)`：清理会话 id，防止路径穿越。

## 调用流程

```text
CliCommands /save
-> SessionStore.save(agent.messages, llm.model, null)
-> normalize(id)
-> 写入 ~/.corecoder/sessions/{id}.json
```

```text
CliCommands /sessions
-> SessionStore.list
-> 读取会话 JSON
-> 生成 SessionInfo
-> CLI 打印列表
```

## 注意点

- 当前 CLI 有保存和列出命令，但没有加载命令。
- `path(...)` 会校验 parent 等于会话根目录，避免非法 id 写到目录外。
- 默认 id 使用时间戳和 UUID 片段生成。
