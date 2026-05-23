# CliBanner.java 源码说明

## 职责

`CliBanner` 负责启动时打印 MisaicCoder 的终端 banner。

## 源码结构

- `RESET`、`ACCENT`、`TITLE`、`MUTED`：ANSI 颜色样式。
- `MIN_RULE_WIDTH`：底部横线最小宽度。
- `print(version, model)`：组装版本和模型信息并选择彩色或纯文本输出。
- `printColorBanner(...)`：输出带颜色 banner。
- `printPlainBanner(...)`：输出无颜色 banner。
- `visualWidth(...)`：估算文本显示宽度。
- `isColorEnabled()`：根据 `NO_COLOR` 和 `TERM=dumb` 判断是否启用颜色。

## 调用流程

```text
Main.main
-> CliBanner.print(Main.VERSION, c.model)
-> isColorEnabled
-> printColorBanner 或 printPlainBanner
```

## 注意点

- 该类只负责展示，不参与 CLI 输入。
- 非彩色终端或设置 `NO_COLOR` 时会输出纯文本。
- `visualWidth` 只对汉字做宽度补偿，emoji 宽度不是完整终端宽度算法。
