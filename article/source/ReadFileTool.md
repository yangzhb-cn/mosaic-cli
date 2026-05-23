# ReadFileTool.java 源码说明

## 职责

`ReadFileTool` 读取文件内容，并按 `行号\t内容` 的格式返回。

## 源码结构

- `parameters()`：声明 `file_path`、`offset`、`limit`。
- `execute(...)`：校验文件、读取文本、按行截取并加行号。

## 调用流程

```text
Agent.exec
-> ReadFileTool.execute(args)
-> file_path -> path(...)
-> Files.exists / Files.isRegularFile
-> Files.readString
-> offset/limit 控制读取范围
-> 拼接 行号\t内容
-> 返回给 Agent
```

## 注意点

- 默认从第 1 行读取，最多 2000 行。
- 目录会返回错误。
- 空文件返回 `(空文件)`。
