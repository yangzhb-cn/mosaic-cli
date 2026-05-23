# BashTool.java 源码说明

## 职责

`BashTool` 执行 shell 命令，并返回 stdout、stderr 和退出码信息。

## 源码结构

- `DANGEROUS`：危险命令正则列表。
- `cwd`：跨命令保存当前工作目录。
- `parameters()`：声明 `command` 和 `timeout`。
- `execute(...)`：校验危险命令、启动进程、读取输出、处理超时。
- `readAsync(...)`：异步读取 stdout/stderr。
- `dangerous(...)`：命中危险命令时返回阻止原因。
- `updateCwd(...)`：命令成功后追踪 `cd` 造成的目录变化。

## 调用流程

```text
Agent.exec
-> BashTool.execute(args)
-> dangerous(command)
-> ProcessBuilder(shell).directory(runDir).start()
-> readAsync(stdout/stderr)
-> waitFor(timeout)
-> 合并输出
-> updateCwd
-> 返回结果给 Agent
```

## 注意点

- 命令通过 `/bin/sh -c` 或 Windows `cmd /c` 执行。
- 超时会强制销毁进程。
- 输出超过 15000 字符会截断。
- 危险命令只做正则拦截，不是完整沙箱。
