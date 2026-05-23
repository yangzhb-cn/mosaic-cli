# Main.java 源码说明

## 职责

`Main` 是程序入口，负责把配置、LLM 客户端、Agent、会话存储和 CLI 交互串起来。

## 源码结构

- `VERSION`：当前 CLI 版本号，供 banner 展示。
- `main`：启动流程入口。
- `Config.fromEnv()`：从环境变量和 `.env` 文件读取 DeepSeek 配置。
- `CliBanner.print(...)`：打印启动 banner。
- `new LlmClient(...)`：创建模型请求客户端。
- `new Agent(...)`：创建主智能体。
- `new SessionStore()`：创建会话存储。
- `CliCommands.repl(...)`：进入终端交互循环。

## 调用流程

```text
java -jar
-> Main.main
-> Config.fromEnv
-> 校验 DEEPSEEK_API_KEY
-> CliBanner.print
-> new LlmClient
-> new Agent
-> new SessionStore
-> CliCommands.repl
```

## 注意点

- 如果 `DEEPSEEK_API_KEY` 为空，程序直接退出。
- `Main` 不解析命令行参数，保持入口简单。
- 真正的对话循环不在 `Main`，而在 `CliCommands`。
