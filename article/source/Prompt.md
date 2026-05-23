# Prompt.java 源码说明

## 职责

`Prompt` 负责生成系统提示词，让模型知道身份、行为规则、工具策略和当前环境。

## 源码结构

- `systemPrompt(...)`：唯一入口，返回完整系统提示词字符串。
- 开头写入 Agent 身份和当前工作目录。
- 文本块包含安全边界、语气、任务流程、工具策略、Git 规则、代码引用规则。
- 末尾遍历工具列表，把工具名和描述追加到提示词。

## 调用流程

```text
Agent 构造
-> Tools.all(this)
-> Prompt.systemPrompt(tools)
-> 保存到 Agent.system
-> Agent.fullMessages
-> 作为 role=system 消息发给 LLM
```

## 注意点

- 工具列表是运行时追加的，因此工具描述变化会反映到 system prompt。
- 当前 CLI 没有富文本 Markdown 渲染，模型输出只是终端纯文本。
- Prompt 不负责工具 schema，schema 由 `Tools.Tool.schema()` 生成。
