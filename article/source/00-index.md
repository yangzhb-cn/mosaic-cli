# 源码逐类说明索引

这个目录按 Java 类拆分说明源码职责和调用流程。

## 启动与 CLI

- [Main](Main.md)
- [CliBanner](CliBanner.md)
- [CliCommands](CliCommands.md)

## Agent 核心

- [Agent](Agent.md)
- [LlmClient](LlmClient.md)
- [ContextManager](ContextManager.md)
- [Prompt](Prompt.md)
- [Config](Config.md)
- [SessionStore](SessionStore.md)

## 工具系统

- [Tools](Tools.md)
- [ToolBase](ToolBase.md)
- [AgentTool](AgentTool.md)
- [BashTool](BashTool.md)
- [GlobTool](GlobTool.md)
- [GrepTool](GrepTool.md)
- [LsTool](LsTool.md)
- [ReadFileTool](ReadFileTool.md)
- [WriteFileTool](WriteFileTool.md)
- [EditFileTool](EditFileTool.md)
- [MultiEditTool](MultiEditTool.md)
- [TodoReadTool](TodoReadTool.md)
- [TodoWriteTool](TodoWriteTool.md)

## 总体调用链

```text
Main
-> Config
-> CliBanner
-> LlmClient
-> Agent
-> SessionStore
-> CliCommands.repl
-> Agent.chat
-> LlmClient.chat
-> Tools.Tool.execute
-> ContextManager.maybeCompress
```
