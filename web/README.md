# CoreCoder Web 工作台

这个目录是 CoreCoder 的本地 Web 工作台前端。后端由 Java 进程提供 API 和 SSE 聊天流，前端由 Next.js 提供页面。

## 前置条件

- JDK 21
- Maven
- Node.js 和 npm
- 项目根目录的 `.env` 已配置 `DEEPSEEK_API_KEY`

不要把 `.env` 内容提交到 Git。

## 打开服务

需要两个终端窗口。

### 1. 启动 Java 后端

在项目根目录执行：

```bash
mvn -q -DskipTests package
java -jar target/core-cli-0.1.0.jar --web
```

默认后端地址是：

```text
http://localhost:8080
```

健康检查：

```bash
curl http://localhost:8080/api/health
```

### 2. 启动 Web 前端

在项目根目录执行：

```bash
cd web
npm install
npm run build
npm run start -- --hostname 127.0.0.1
```

打开页面：

```text
http://127.0.0.1:3000
```

## 开发模式

如果正在改前端，用开发模式：

```bash
cd web
npm run dev -- --hostname 127.0.0.1
```

开发模式仍然访问：

```text
http://127.0.0.1:3000
```

## 关闭服务

如果服务是在当前终端里启动的，直接按 `Ctrl+C`。

如果要按端口关闭：

```bash
pids=$(lsof -tiTCP:8080 -sTCP:LISTEN); [ -n "$pids" ] && kill $pids
pids=$(lsof -tiTCP:3000 -sTCP:LISTEN); [ -n "$pids" ] && kill $pids
```

## 查看端口占用

```bash
lsof -iTCP:8080 -sTCP:LISTEN -n -P
lsof -iTCP:3000 -sTCP:LISTEN -n -P
```

## 常见问题

### 端口被占用

先查看占用：

```bash
lsof -iTCP:8080 -sTCP:LISTEN -n -P
lsof -iTCP:3000 -sTCP:LISTEN -n -P
```

确认可以关闭后，再用“关闭服务”里的命令。

### 页面打不开

确认前端进程还在：

```bash
lsof -iTCP:3000 -sTCP:LISTEN -n -P
```

确认后端健康检查正常：

```bash
curl http://localhost:8080/api/health
```

### 页面能打开但发消息失败

检查 Java 后端终端输出，并确认项目根目录 `.env` 中有 `DEEPSEEK_API_KEY`。
