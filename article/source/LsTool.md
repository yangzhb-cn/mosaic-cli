# LsTool.java 源码说明

## 职责

`LsTool` 列出绝对路径目录下的文件和子目录。

## 源码结构

- `parameters()`：声明 `path` 和可选 `ignore`。
- `execute(...)`：校验绝对路径、读取目录、过滤忽略项。
- `ignored(...)`：根据 glob 模式判断是否忽略。

## 调用流程

```text
Agent.exec
-> LsTool.execute(args)
-> 校验 path 是绝对路径
-> Files.isDirectory
-> Files.list(dir)
-> ignored(dir, p, ignore)
-> 文件名排序
-> 目录追加 /
-> 返回列表
```

## 注意点

- `LS` 强制要求 `path` 是绝对路径。
- `ignore` 可以匹配文件名或相对路径。
- 空目录返回 `(空目录)`。
