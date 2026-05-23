# WriteFileTool.java 源码说明

## 职责

`WriteFileTool` 创建新文件，或完整覆盖已有文件。

## 源码结构

- `parameters()`：声明 `file_path` 和 `content`。
- `execute(...)`：创建父目录、写入内容、记录变更文件。

## 调用流程

```text
Agent.exec
-> WriteFileTool.execute(args)
-> file_path -> path(...)
-> Files.createDirectories(parent)
-> Files.writeString
-> Tools.markChanged
-> 返回写入行数
```

## 注意点

- 这是覆盖写入，不是追加。
- 写入后会进入 `/diff` 展示的 changed files 集合。
- 行数通过换行符数量估算。
