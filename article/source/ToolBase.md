# ToolBase.java 源码说明

## 职责

`ToolBase` 是工具基类，封装 schema 构造、参数读取、列表转换和路径处理。

## 源码结构

- `params(...)`：生成 JSON schema 的 object 参数结构。
- `prop(...)`：生成普通字段 schema。
- `arrayProp(...)`：生成数组字段 schema。
- `str(...)`：从参数中读取字符串。
- `integer(...)`：从参数中读取整数。
- `list(...)`：把任意 List 转成 `List<String>`。
- `mapList(...)`：把任意 List 转成 `List<Map<String, Object>>`。
- `path(...)`：展开 `~` 并转成绝对规范路径。

## 调用流程

```text
具体工具 parameters()
-> params(...)
-> prop(...) / arrayProp(...)
-> Tools.Tool.schema()
-> 发给 LLM
```

```text
具体工具 execute(args)
-> str / integer / list / mapList / path
-> 执行业务逻辑
```

## 注意点

- `path(...)` 会把相对路径转成绝对路径，但不是所有工具都允许相对路径。
- `mapList(...)` 会做泛型擦除后的安全转换，避免直接强转异常。
