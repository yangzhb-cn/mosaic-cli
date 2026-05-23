# Tools.java 源码说明

## 职责

`Tools` 是工具注册中心，定义工具接口、创建工具列表，并维护会话级工具状态。

## 源码结构

- `Tool`：所有工具必须实现的接口。
- `schema()`：把工具转换成 LLM function calling schema。
- `all(parent)`：创建当前 Agent 可用的工具列表。
- `get(tools, name)`：按名称查找工具。
- `CHANGED`：记录当前会话修改过的文件。
- `TODOS`：保存当前会话 Todo 列表。
- `markChanged(...)`：记录文件修改。
- `replaceTodos(...)`、`todosJson()`：维护 Todo 状态。

## 调用流程

```text
Agent 构造
-> Tools.all(this)
-> Agent.toolSchemas
-> tools.stream().map(Tools.Tool::schema)
-> LlmClient 请求体 tools 字段
```

```text
Agent.exec
-> Tools.get(tools, tc.name())
-> tool.execute(arguments)
```

## 注意点

- 工具名必须和模型返回的 function name 完全一致。
- `CHANGED` 和 `TODOS` 是静态状态，属于当前 JVM 会话级别。
- `Task` 工具需要父 Agent，因此 `Tools.all(null)` 时只能用于 schema 或测试部分行为。
