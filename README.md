# Mosaic CLI Java

Mosaic CLI 是一个本地运行的 Java 编码 Agent。它在你的项目目录里启动，通过命令行对话完成代码阅读、文件修改、命令执行、联网搜索、计划执行、长期记忆和多会话恢复。

这个项目偏 MVP 风格：功能够用、代码直接、便于二次开发。

## 功能

- 交互式 CLI：JLine 命令行，支持 `/` 命令补全。
- 编码工具：读写文件、搜索文件、执行 Shell、WebSearch、WebFetch。
- Plan-and-Execute：`/plan` 生成 DAG，`/act` 按依赖并行执行。
- 多 Session：自动保存到 JSONL，启动恢复上次会话，可新建和切换。
- 长期记忆：读取 `workspace/Mosaic.md` 注入 system-reminder，可手动 `/memory update`。
- 工具审计：`/audit` 查看工具调用统计，`/audit save` 保存快照。
- MCP / Skills：启动时加载本地 MCP 和 Skill 配置。
- 后台任务：支持一次性和周期性 schedule 任务。
- 可选 Telegram：配置后可通过 IM 调用 Agent。

## 环境要求

- JDK 21
- Maven 3.9+

## 快速开始

```bash
git clone git@github.com:<your-name>/<your-repo>.git
cd <your-repo>
cp .env.example .env
```

编辑 `.env`，至少填入：

```env
DEEPSEEK_API_KEY=your_api_key
```

运行测试：

```bash
mvn test
```

打包：

```bash
mvn -DskipTests package
```

启动：

```bash
java -jar target/mosic-cli-0.1.0.jar
```

Mosaic CLI 会把启动时所在目录当作工作目录。要处理别的项目，就在目标项目目录里运行 jar：

```bash
cd /path/to/your/project
java -jar /path/to/mosaic-cli/target/mosic-cli-0.1.0.jar
```

也可以加一个 shell 函数：

```bash
mosaic() {
  java -jar /path/to/mosaic-cli/target/mosic-cli-0.1.0.jar "$@"
}
```

之后在任意项目目录运行：

```bash
mosaic
```

## 配置

配置优先级：环境变量 > 从当前目录向上查找的 `.env` > 默认值。

参考 [.env.example](.env.example)：

```env
DEEPSEEK_MODEL=deepseek-v4-flash/pro
DEEPSEEK_API_KEY=your_api_key
DEEPSEEK_BASE_URL=https://api.deepseek.com

TAVILY_API_KEY=your_tavily_key
MOSAIC_SCHEDULE_INTERVAL_SECONDS=30

TELEGRAM_BOT_TOKEN=your_bot_token
OWNER_ID=your_user_id
```

说明：

- `DEEPSEEK_API_KEY`：必填，启动 CLI 需要。
- `TAVILY_API_KEY`：可选，只在使用 `WebSearch` 时需要。
- `MOSAIC_SCHEDULE_INTERVAL_SECONDS`：后台定时任务扫描间隔，默认 30 秒。
- Telegram 配置可选；同时配置 `TELEGRAM_BOT_TOKEN` 和 `OWNER_ID` 后会自动启用。

## MCP 与 Skills

MCP 配置文件：

```text
~/.mosaiccoder/mcp.json
```

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/Users"
      ]
    },
    "context7": {
      "command": "npx",
      "args": [
        "-y",
        "@upstash/context7-mcp@latest"
      ]
    },
    "datetime": {
      "type": "stdio",
      "command": "npx",
      "args": [
        "-y",
        "@odgrim/mcp-datetime"
      ]
    }
  }
}
```

Skill 文件：

```text
~/.mosaiccoder/skills/<name>/SKILL.md
```

启动时只加载 Skill 元数据，正文由 `ReadSkill` 工具按需读取。

## 常用命令

```text
/reset               清空当前 session 的 messages、audit、当前 plan
/tokens              查看 token 使用情况
/compact             压缩上下文
/diff                查看当前会话修改过的文件
/mcp                 查看 MCP 加载状态
/last-request        查看上一轮发给 LLM 的完整 JSON 请求
/audit               查看工具调用统计表格
/audit save          保存审计快照
/session             展示当前 active session 和 user messages
/session list        展开所有 session，* 标记 active
/session new [id]    创建并切换到新 session
/session switch <id> 切换到已有 session
/memory update       用当前 session 提炼并更新 workspace/Mosaic.md
/plan                进入规划输入状态
/act                 执行当前计划
/cancel              取消当前计划
/exit                退出 CLI
```

## Session 与长期记忆

Session 是可切换的运行上下文，自动保存到当前项目目录：

```text
data/state.json
data/sessions/YYYY/MM/DD/session_<timestamp>_<id>.jsonl
```

每行 JSONL 结构为 `timestamp/type/payload`：

- `response_item`：可恢复上下文，包含 user、assistant 和 tool message。
- `event_msg`：工具调用、子 Agent、Plan 或 Schedule 过程线索，不参与上下文恢复。
- `session_reset` / `context_reset`：恢复边界。

系统提示词会引导模型在需要历史信息时搜索 `data/sessions/**/*.jsonl`，不会把全部历史自动塞进上下文。

长期记忆文件：

```text
workspace/Mosaic.md
```

`Mosaic.md` 每轮动态读取并注入 `<system-reminder>`，不会写进 session messages。`/memory update` 会调用一次 LLM，把当前 session 中长期有效的信息提炼成新的完整 `Mosaic.md`。

## Plan-and-Execute

`/plan` 会等待下一条普通输入，并交给独立 `PlannerAgent` 生成严格 JSON DAG。Planner 只允许只读/搜索工具：

```text
Read, LS, Glob, Grep, WebFetch, WebSearch
```

解析成功后 CLI 打印计划表格，并提示：

```text
/act 执行，/plan 重新规划，/cancel 取消。
```

`/act` 按 DAG 依赖调度，默认并发 4。`FILE_WRITE` 和 `COMMAND` 类型任务串行执行。某个 task 连续 3 次失败后停止调度新任务，保留已完成和失败结果。

## 开发

运行测试：

```bash
mvn test
```

完整干净验证：

```bash
mvn clean test
```

打包可执行 fat jar：

```bash
mvn -DskipTests package
```

## 项目结构

```text
src/main/java/com/yang/
  Main.java      # 程序入口和启动装配
  agent/         # 核心 Agent 和子 Agent
  audit/         # 工具调用审计
  cli/           # REPL、命令路由、CLI plan 编排
  config/        # 环境变量和 .env 配置
  context/       # 上下文估算、清理、压缩
  im/            # IM 抽象和 Telegram 实现
  llm/           # OpenAI 兼容 LLM 客户端
  mcp/           # MCP 配置、加载和工具包装
  memory/        # Mosaic.md 和长期记忆更新
  plan/          # Planner、DAG 模型、解析、执行
  prompt/        # 系统提示词和动态 reminder
  schedule/      # 后台计划任务
  session/       # JSONL 多 session
  skill/         # 本地 Skill 加载
  tool/          # 内置工具和工具执行器
```

