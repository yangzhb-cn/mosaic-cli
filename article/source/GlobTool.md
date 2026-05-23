# GlobTool.java 源码说明

## 职责

`GlobTool` 按 glob 模式查找文件路径。

## 源码结构

- `name()`：工具名 `Glob`。
- `parameters()`：声明 `pattern` 和可选 `path`。
- `execute(...)`：遍历目录，匹配文件，按修改时间排序。
- `mtime(...)`：读取文件修改时间，用于排序。

## 调用流程

```text
Agent.exec
-> GlobTool.execute(args)
-> pattern = args["pattern"]
-> base = path(args["path"] 或 ".")
-> Files.walk(base)
-> PathMatcher("glob:" + pattern)
-> 命中文件按修改时间倒序
-> 返回最多 100 条
```

## 注意点

- `path` 默认为当前工作目录。
- 匹配会同时尝试相对路径和文件名。
- 命中超过 100 个时只显示前 100 个。
