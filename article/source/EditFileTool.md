# EditFileTool.java 源码说明

## 职责

`EditFileTool` 对单个文件做一次精确字符串替换，并校验替换次数。

## 源码结构

- `parameters()`：声明 `file_path`、`old_string`、`new_string`、`expected_replacements`。
- `execute(...)`：读取文件、计数、替换、写回、返回 diff。
- `count(...)`：统计 `old_string` 出现次数。
- `diff(...)`：生成简化 diff。

## 调用流程

```text
Agent.exec
-> EditFileTool.execute(args)
-> Files.readString
-> count(old, old_string)
-> count 必须等于 expected_replacements
-> old.replace(old_string, new_string)
-> Files.writeString
-> Tools.markChanged
-> diff(old, next)
-> 返回结果
```

## 注意点

- `old_string` 必须精确匹配，包括空格和换行。
- 找不到时会返回文件开头，帮助模型重新定位。
- diff 超过长度会截断。
