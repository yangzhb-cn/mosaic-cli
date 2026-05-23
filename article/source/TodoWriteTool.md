# TodoWriteTool.java 源码说明

## 职责

`TodoWriteTool` 创建或替换当前会话的 Todo 列表。

## 源码结构

- `parameters()`：声明 `todos` 数组，每个 todo 包含 `id`、`content`、`status`、`priority`。
- `execute(...)`：把参数转换为列表，写入 `Tools` 的静态 Todo 状态。

## 调用流程

```text
Agent.exec
-> TodoWriteTool.execute(args)
-> mapList(args["todos"])
-> Tools.replaceTodos
-> Tools.todoCount
-> 返回更新数量
```

## 注意点

- 这是整体替换，不是增量追加。
- Todo 状态只在当前 JVM 会话中存在。
- schema 要求每条 todo 四个字段都存在。
