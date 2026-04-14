## ricbot

一个可扩展的 Java CLI Agent 框架（Java 17+），当前仓库收敛为“最小可跑 + 可展示”的主链路版本：

- CLI：`ricbot` 命令启动（无需手敲 `java -jar`）
- Core：agent loop / runner / session & history 落盘 / message bus / hook
- Tool：默认只启用 `read_file`、`list_dir`、`exec`
- LLM：一个真实可用的 OpenAI-compatible provider（JDK `HttpClient` + Jackson）

### 目录结构（最小发布面）

- `src/main/java/ricbot/cli`：命令行入口与交互渲染
- `src/main/java/ricbot/core`：agent loop/runner/session/message/hook
- `src/main/java/ricbot/tool`：工具定义与工具注册表（最小工具集）
- `src/main/java/ricbot/llm`：openai-compatible provider
- `src/main/java/ricbot/infra/config`：配置加载/迁移/默认路径
- `config/ricbot.config.json`：示例配置
- `bin/ricbot`、`bin/ricbot.cmd`：启动包装脚本

### 构建

```bash
sh ./mvnw test
sh ./mvnw package
```

产物在 `target/Ricbot-*.jar`。

### 启动（推荐）

首次使用把脚本设为可执行：

```bash
chmod +x ./ricbot ./bin/ricbot
```

查看版本：

```bash
./ricbot --version
```

### 配置

最简单方式：直接用仓库内示例配置（推荐先复制一份再改）：

```bash
cp ./config/ricbot.config.json ./ricbot.config.json
```

配置文件支持 `${ENV_VAR}` 形式的环境变量引用（例如 `${OPENAI_API_KEY}`）。

也可以通过 CLI 初始化生成配置：

```bash
./ricbot onboard --config ./ricbot.config.json --workspace .
```

### Demo：跑通一次真实链路（模型 + 工具 + session 落盘）

准备环境变量（OpenAI-compatible）：

```bash
export OPENAI_API_KEY="YOUR_KEY"
```

发一条消息（非交互）：

```bash
./ricbot agent --config ./ricbot.config.json --message "请用工具先 list_dir 列出当前工作目录，然后 read_file 读取 pom.xml 的前 60 行，最后用 exec 执行 'java -version'，并总结三步结果。"
```

交互模式：

```bash
./ricbot agent --config ./ricbot.config.json
```

默认 session/history 会落在 workspace 下的 `sessions/` 目录中。

### 可用命令

- `./ricbot --version`
- `./ricbot onboard --config <path> --workspace <path>`
- `./ricbot agent [--config <path>] [--session <id>] [--message <text>] [--no-markdown]`
- `./ricbot status`
- `./ricbot provider`

