# Mosaic CLI Java

一个简洁的 Java Agent CLI 项目，支持命令行交互、工具调用、子 Agent、可选 Telegram IM、Tavily 联网搜索、最简 MCP 和本地 Skills。

## 功能概览

- CLI 对话：默认启动交互式命令行。
- 工具系统：文件读写、搜索、Shell、Todo、子 Agent、WebFetch、WebSearch。
- 联网能力：`WebFetch` 直接抓取 URL，`WebSearch` 调 Tavily Search API。
- MCP：启动时读取 `~/.mosaiccoder/mcp.json`，加载 stdio / HTTP / SSE MCP server 的 tools。
- Skills：启动时读取 `~/.mosaiccoder/skills/*/SKILL.md`，注入系统提示词。
- 会话上下文：支持会话存储和上下文压缩。
- 工具审计：支持 `/audit` 查看当前对话工具调用统计，`/audit save` 追加保存审计快照。
- 可选 IM：配置 Telegram 后可把消息转给 Agent。

## 环境要求

- JDK 21
- Maven 3.9+

## 配置

配置优先级：环境变量 > 从当前目录向上查找的 `.env` > 默认值。

常用变量：

```env
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_API_KEY=your_api_key
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
TAVILY_API_KEY=your_tavily_key

# 可选 Telegram
MISAIC_IM=telegram
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_OWNER_ID=your_user_id
```

`DEEPSEEK_API_KEY` 是启动 CLI 必需项。`TAVILY_API_KEY` 只在调用 `WebSearch` 工具时需要。

MCP 配置文件：

```json
{
  "mcpServers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": {}
    },
    "remote": {
      "type": "http",
      "url": "http://localhost:3000",
      "endpoint": "/mcp",
      "headers": {}
    },
    "legacy-sse": {
      "type": "sse",
      "url": "http://localhost:3001",
      "endpoint": "/sse",
      "headers": {}
    }
  }
}
```

`type` 可选值：`stdio`、`http`/`streamable-http`、`sse`。旧的 stdio 配置不写 `type` 也兼容。

Skill 文件放在 `~/.mosaiccoder/skills/<name>/SKILL.md`。启动时会加载全部 Skill，不做关键词匹配。

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

从其他文件夹使用：

```bash
cd /path/to/your/project
java -jar /Users/yangzhibin/projects/i/agent/core-cli-java/target/core-cli-0.1.0.jar
```

MosaicCoder 会把你执行 `java -jar` 时所在的目录作为工作目录。也就是说，如果要让它处理其他项目，不需要进入 `core-cli-java`，只要在目标项目目录里启动这个 jar。

也可以加一个 shell 函数，方便在任意目录启动：

```bash
mosaiccoder() {
  java -jar /Users/yangzhibin/projects/i/agent/core-cli-java/target/core-cli-0.1.0.jar
}
```

查看 MCP 状态：

```text
/mcp
```

查看当前对话工具调用统计：

```text
/audit
```

输出列包括 `Tool`、`Calls`、`Success`、`Success_Rate`、`Avg_ms`。保存当前统计快照：

```text
/audit save
```

审计快照会按当前 `conversation_id` 追加到 `~/.mosaiccoder/audits/audit_<conversation_id>.jsonl`，同一个 session 多次保存会追加多行。

保存和恢复会话：

```text
/save
/load <session_id>
```

`/save` 会保存 `messages` 和 `conversation_id`；`/load <session_id>` 会恢复这两项，但不会恢复历史 audit 统计。

## 项目结构

```text
src/main/java/com/coder/
  Main.java              # 程序入口
  Agent.java             # Agent 主循环和工具调用编排
  LlmClient.java         # LLM HTTP 客户端
  Config.java            # 环境变量和 .env 配置读取
  Prompt.java            # 系统提示词
  audit/                 # 工具调用审计统计和 JSONL 保存
  cli/                   # 命令行交互
  im/                    # Telegram IM 接入
  mcp/                   # MCP 加载和工具包装
  skill/                 # 本地 Skill 加载
  tools/                 # 工具实现和注册
```

## 开发约定

- 保持代码简洁，优先匹配当前项目风格。
- 不为单次需求加复杂抽象。
- 改动后至少运行：

```bash
mvn clean test
```
