# GrepTool.java 源码说明

## 职责

`GrepTool` 使用 Java 正则搜索文件内容。

## 源码结构

- `SKIP`：遍历目录时跳过的目录名。
- `parameters()`：声明 `pattern`、`path`、`include`。
- `execute(...)`：编译正则、收集文件、逐行匹配。
- `walk(...)`：遍历目录并按 include 过滤文件。

## 调用流程

```text
Agent.exec
-> GrepTool.execute(args)
-> Pattern.compile(pattern)
-> path 是文件则直接搜索
-> path 是目录则 walk(path, include)
-> Files.readString(fp).lines()
-> 正则逐行匹配
-> 返回 file:line: text
```

## 注意点

- 正则非法会直接返回错误。
- 默认跳过 `.git`、`node_modules`、`target` 等目录。
- 最多返回 200 条匹配。
