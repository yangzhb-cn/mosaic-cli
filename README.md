# Core CLI Java

一个简洁的 Java Agent CLI 项目，支持命令行交互、工具调用、子 Agent、可选 Telegram IM，以及 Tavily 联网搜索。

## 功能概览

- CLI 对话：默认启动交互式命令行。
- 工具系统：文件读写、搜索、Shell、Todo、子 Agent、WebFetch、WebSearch。
- 联网能力：`WebFetch` 直接抓取 URL，`WebSearch` 调 Tavily Search API。
- 会话上下文：支持会话存储和上下文压缩。
- 可选 IM：配置 Telegram 后可把消息转给 Agent。

## 环境要求

- JDK 21
- Maven 3.9+

## 配置

配置优先级：环境变量 > 从当前目录向上查找的 `.env` > 默认值。

常用变量：

```env
DEEPSEEK_API_KEY=your_api_key
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
TAVILY_API_KEY=your_tavily_key

# 可选 Telegram
MISAIC_IM=telegram
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_OWNER_ID=your_user_id
```

`DEEPSEEK_API_KEY` 是启动 CLI 必需项。`TAVILY_API_KEY` 只在调用 `WebSearch` 工具时需要。

## 运行

开发测试：

```bash
mvn clean test
```

打包：

```bash
mvn -DskipTests package
```

启动 CLI：

```bash
java -jar target/core-cli-0.1.0.jar
```

## 项目结构

```text
src/main/java/com/coder/
  Main.java              # 程序入口
  Agent.java             # Agent 主循环和工具调用编排
  LlmClient.java         # LLM HTTP 客户端
  Config.java            # 环境变量和 .env 配置读取
  Prompt.java            # 系统提示词
  cli/                   # 命令行交互
  im/                    # Telegram IM 接入
  tools/                 # 工具实现和注册
```

## 开发约定

- 保持代码简洁，优先匹配当前项目风格。
- 不为单次需求加复杂抽象。
- 改动后至少运行：

```bash
mvn clean test
```
