# Mosaic CLI Java

一个 MVP 风格的 Java Agent CLI。核心目标是：在本地项目目录里启动一个可读、可改、可扩展的编码 Agent。

## 功能概览

- CLI 对话：JLine 交互式命令行，支持斜杠命令补全。
- 工具系统：文件读写、搜索、Shell、Todo、子 Agent、WebFetch、WebSearch。
- Plan-and-Execute：`/plan` 生成最小 DAG，`/act` 按依赖并行执行。
- MCP：启动时读取 `~/.mosaiccoder/mcp.json`，加载 stdio / HTTP / SSE MCP tools。
- Skills：启动时读取 `~/.mosaiccoder/skills/*/SKILL.md`，通过 `ReadSkill` 按需使用。
- 会话：支持保存、加载、查看当前或已保存会话的用户消息。
- 工具审计：`/audit` 查看统计，`/audit save` 保存 JSONL 快照。
- 可选 IM：配置 Telegram 后可把消息转给 Agent。

## 环境要求

- JDK 21
- Maven 3.9+

## 配置

配置优先级：环境变量 > 从当前目录向上查找的 `.env` > 默认值。

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

MCP 配置文件：`~/.mosaiccoder/mcp.json`

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

Skill 文件放在 `~/.mosaiccoder/skills/<name>/SKILL.md`。启动时会加载 Skill 元数据，正文由 `ReadSkill` 工具按需读取。

## 本地运行

测试：

```bash
mvn test
```

打包：

```bash
mvn -DskipTests package
```

启动 CLI：

```bash
java -jar target/core-cli-0.1.0.jar
```

Mosaic CLI 会把执行 `java -jar` 时所在目录作为工作目录。要让它处理其他项目，就在目标项目目录里启动这个 jar：

```bash
cd /path/to/your/project
java -jar /path/to/mosaic-cli/target/core-cli-0.1.0.jar
```

也可以加一个 shell 函数：

```bash
mosaic() {
  java -jar /path/to/mosaic-cli/target/core-cli-0.1.0.jar "$@"
}
```

然后在任意项目目录运行：

```bash
mosaic
```

## 分享给别人使用

这个项目是 CLI，不是常驻 Web 服务，最小分享方式不需要部署服务器。

推荐两种方式：

1. 分享源码仓库
   - 对方安装 JDK 21 和 Maven。
   - 克隆仓库后执行 `mvn test` 或 `mvn -DskipTests package`。
   - 配置自己的 `DEEPSEEK_API_KEY` 后运行 `java -jar target/core-cli-0.1.0.jar`。

2. 分享可执行 jar
   - 你本机执行 `mvn -DskipTests package`。
   - 把 `target/core-cli-0.1.0.jar` 发给对方。
   - 对方只需要 JDK 21 和自己的环境变量或 `.env`：

```bash
export DEEPSEEK_API_KEY=your_api_key
java -jar core-cli-0.1.0.jar
```

如果要做更正式的分发，可以把 jar、README、示例 `.env` 说明打成 zip，或放到 GitHub Release。当前 Maven 已使用 `maven-shade-plugin` 生成包含依赖的可执行 fat jar，暂时不需要额外部署逻辑。

## 常用命令

```text
/reset             清空 messages、audit、当前 plan
/tokens            查看 token 使用情况
/compact           压缩上下文
/diff              查看当前会话修改过的文件
/mcp               查看 MCP 加载状态
/last-request      查看上一轮发给 LLM 的完整 JSON 请求
/audit             查看工具调用统计表格
/audit save        保存审计快照
/save              保存当前会话
/load <id>         恢复 messages 和 conversation_id
/session           展示当前会话的 user messages
/session <id>      只读展示指定已保存会话的 user messages
/session list      展开保存的会话列表
/plan              进入规划输入状态
/act               执行当前计划
/cancel            取消当前计划
/exit              退出 CLI
```

## Plan-and-Execute

`/plan` 会让 CLI 等待下一条普通输入，并把这条输入交给独立 `PlannerAgent`。Planner 只使用只读/搜索工具：

```text
Read, LS, Glob, Grep, WebFetch, WebSearch
```

Planner 最终输出严格 JSON DAG。解析成功后 CLI 打印计划表格，并提示：

```text
/act 执行，/plan 重新规划，/cancel 取消。
```

`/act` 按 DAG 依赖调度，默认并发 4。`FILE_WRITE` 和 `COMMAND` 类型任务串行执行。某个 task 连续 3 次失败后停止调度新任务，保留已完成和失败结果。

MVP 暂不支持 `/plan <task>`、IM/Telegram Plan、计划持久化、人工编辑计划，也不接 Todo。

## 工具审计

`/audit` 输出当前内存统计：

```text
Tool        Calls  Success  Success_Rate  Avg_ms
Read        12     12       100.00%       4.2
Bash        5      4        80.00%        120.8
```

`/audit save` 会追加保存一行 JSON 到：

```text
~/.mosaiccoder/audits/audit_<conversation_id>.jsonl
```

同一个 session 多次保存会追加多行；不同 session 使用不同 `conversation_id` 文件。

## 项目结构

```text
src/main/java/com/yang/
  Main.java              # 程序入口和启动装配
  agent/                 # 核心 Agent
  audit/                 # 工具调用审计
  cli/                   # REPL、命令路由、CLI plan 编排
  config/                # 环境变量和 .env 配置
  context/               # 上下文估算、清理、压缩
  im/                    # IM 抽象和 Telegram 实现
  llm/                   # OpenAI 兼容 LLM 客户端
  mcp/                   # MCP 配置、加载和工具包装
  plan/                  # Planner、DAG 模型、解析、执行
  prompt/                # 系统提示词和动态 reminder
  session/               # 会话保存、加载、列表
  skill/                 # 本地 Skill 加载
  tool/                  # 内置工具、工具执行器和注册
```

## 开发约定

- 保持代码简洁，优先 MVP。
- 不为单次需求加复杂抽象。
- 改动后至少运行：

```bash
mvn test
```
