# AgentTool.java 源码说明

## 职责

`AgentTool` 暴露 `Task` 工具，让模型启动一个子智能体处理复杂子任务。

## 源码结构

- `parent`：父 Agent，用于创建子 Agent。
- `parameters()`：声明 `task` 字段。
- `execute(...)`：调用父 Agent 的 `runSubAgent(...)`。

## 调用流程

```text
Agent.exec
-> AgentTool.execute(args)
-> parent.runSubAgent(task, 20)
-> 子 Agent 使用去掉 Task 的工具列表
-> 子 Agent 完成后返回结果
-> 超长结果截断
-> 返回给父 Agent
```

## 注意点

- 如果 `parent == null`，会返回未初始化错误。
- 子 Agent 会过滤掉 `Task` 工具，避免递归创建子 Agent。
- 返回结果超过 5000 字符会截断。
