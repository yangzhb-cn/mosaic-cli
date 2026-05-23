# MultiEditTool.java 源码说明

## 职责

`MultiEditTool` 对同一个文件按顺序执行多处精确字符串替换，全部校验通过后才写回。

## 源码结构

- `parameters()`：声明 `file_path` 和 `edits` 数组。
- `execute(...)`：读取原文，逐个应用编辑，最后一次性写入。
- `count(...)`：统计每个 `old_string` 出现次数。

## 调用流程

```text
Agent.exec
-> MultiEditTool.execute(args)
-> Files.readString
-> next = original
-> 遍历 edits
-> 校验 old_string != new_string
-> 校验 count(next, old_string) == expected_replacements
-> next = next.replace(...)
-> 所有 edit 成功后 Files.writeString
-> Tools.markChanged
-> 返回应用编辑数
```

## 注意点

- 编辑按顺序应用，后一个 edit 看到的是前一个 edit 的结果。
- 中途任何一个 edit 校验失败，都不会写入文件。
- 适合同一文件多处稳定替换。
