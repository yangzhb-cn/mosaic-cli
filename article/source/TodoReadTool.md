# TodoReadTool.java 源码说明

## 职责

`TodoReadTool` 读取当前会话的 Todo 列表。

## 源码结构

- `name()`：工具名 `TodoRead`。
- `parameters()`：无参数。
- `execute(...)`：返回 `Tools.todosJson()`。

## 调用流程

```text
Agent.exec
-> TodoReadTool.execute(args)
-> Tools.todosJson()
-> JSON pretty print
-> 返回给模型
```

## 注意点

- Todo 数据保存在 `Tools` 的静态列表中。
- 当前实现是内存态，不会持久化到磁盘。
