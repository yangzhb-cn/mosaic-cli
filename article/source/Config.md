# Config.java 源码说明

## 职责

`Config` 负责加载运行配置，并统一提供给 `Main`。

## 源码结构

- `MODEL`：固定模型名 `deepseek-v4-flash`。
- `apiKey`：DeepSeek API key。
- `baseUrl`：DeepSeek API 地址，默认 `https://api.deepseek.com/v1`。
- `temperature`：模型温度，默认 `0.0`。
- `maxContextTokens`：上下文压缩阈值基准，默认 `128000`。
- `fromEnv()`：从系统环境变量和当前目录向上的 `.env` 加载配置。
- `value(...)`：实现优先级：环境变量 > `.env` > 默认值。
- `loadDotenv(...)`：从当前目录向上查找 `.env`，最多找到用户主目录。
- `parseDotenv(...)`：解析简单 `KEY=value` 格式。

## 调用流程

```text
Main.main
-> Config.fromEnv
-> Config.from(System.getenv(), cwd)
-> loadDotenv(cwd)
-> parseDotenv(.env)
-> value(env, file, key, default)
-> 返回 Config
```

## 注意点

- 只读取 `DEEPSEEK_API_KEY` 和 `DEEPSEEK_BASE_URL`。
- 不再读取其他模型或兼容别名配置。
- `.env` 解析是轻量实现，不支持复杂 shell 表达式。
