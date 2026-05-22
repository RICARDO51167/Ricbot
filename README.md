# Ricbot

> 一个基于 Java 17 的可运行智能 Agent 系统骨架，打通了 CLI、OpenAI 兼容 API、多渠道接入、工具调用、会话持久化、长期记忆、Cron、MCP 与子代理主链路。

- 统一入口与统一主循环：CLI、API、Channel 最终都汇入 `AgentLoop`
- 主链路可跑通：Provider 调用、Tool Calling、Session、Memory、Cron、Dream 均已有实现
- 上下文工程升级：支持分层上下文、相关性召回、结构化工具摘要与任务状态恢复
- 扩展点清晰：Provider、Tool、MCP、Channel、Skill 都有独立装配层
- 不是简单 Demo：包含安全限制、流式输出、断点恢复、会话落盘、Git 化记忆管理

## 2. 项目简介

Ricbot 是一个面向“可工程化智能代理”的 Java 项目。它试图解决的不是单次调用大模型，而是如何把大模型能力组织成一个可持续运行、可接入多入口、可接工具、可持久化上下文、可逐步扩展的 Agent Runtime。

从代码现状看，这个项目已经具备“可运行骨架 + 主链路可用 + 多扩展点开放”的特点：

- 可以通过 CLI 单次调用或交互模式直接使用 Agent
- 可以通过 `serve` 启动 OpenAI 兼容 API，并同时拉起已启用渠道
- 可以把文件系统、命令执行、Web、Cron、MCP、Subagent 暴露给模型调用
- 可以把会话、记忆、归档、Dream 维护在工作区内，形成长期上下文

适合的场景包括：

- 个人 Agent Runtime / 本地智能助手框架
- 多渠道机器人统一内核
- 面试或简历中的“工程化 Agent 系统”项目展示
- 后续扩展为 Web 控制台、企业机器人、MCP Hub 的技术底座

项目核心设计思路可以概括为三点：

1. 所有入口先标准化为消息，再进入统一 Agent 主循环。
2. 所有外部能力先标准化为 Tool / Provider / Channel / MCP 适配层。
3. 所有长期状态尽量落到工作区文件中，便于调试、迁移与回溯。

> 说明：当前运行时、入口类与主包名以 `ricbot` 为准。仓库中少量旧命名仅作为兼容入口保留，例如旧环境变量 fallback。

## 核心闭环

Ricbot 当前定位：

```text
Java Agent Runtime with Context Engineering, Safe Tool Execution,
Experience Memory and Eval-driven Learning
```

它不是只展示一次模型调用，而是把“任务执行 -> 安全工具 -> 工程审查 -> 任务沉淀 -> 经验治理 -> 评测学习”串成一个可复现闭环。

文字架构图：

```text
User / CLI / API / Channel
  -> MessageBus
  -> AgentLoop
     -> ContextSelectionService
        -> memory / notes / RAG / tool trace / task state / verified experience
     -> Provider
     -> ToolRegistry
        -> risk analyzer -> approval request -> /approve resume
        -> filesystem/process tools -> DiffReview
     -> TaskSummaryService
        -> /summary -> TaskNoteWriter -> notes/tasks
     -> ExperienceExtractor
        -> candidates.jsonl -> manual verify -> verified.jsonl
        -> ContextSelectionService verified_experience
     -> ExperiencePromoter -> notes/project playbook
     -> EvalHarness
        -> artifacts -> eval learn -> candidate experience
```

核心能力表：

| 能力 | 当前闭环位置 | 说明 |
| --- | --- | --- |
| Context Engineering | `ContextSelectionService` / `/context` | 分层选择 recent history、task state、memory、notes、workspace knowledge、tool trace 和 verified experience，并暴露 token/source 观测 |
| Safe Execution | `CommandRiskAnalyzer` / `ApprovalService` / `/approve` | MEDIUM/HIGH 工具调用先生成 approval request，审批后恢复原 pending tool call，BLOCKED 直接拒绝 |
| Diff Review | `DiffReviewService` / file tools | 文件变更后返回 changed files、风险提示、suspicious changes、suggested tests 和 rollback hint |
| Task Notes | `TaskSummaryService` / `TaskNoteWriter` | `/summary` 可读摘要，`/summary --write-note` 显式写入 `notes/tasks` 并更新索引 |
| Experience Governance | `ExperienceStore` / `/experience` | 经验先进入 candidate，经人工 verify 后才进入 verified；promote 必须人工触发 |
| Worktree-backed Team Execution | `TeamExecutionService` / `/team run --worktree` | 为 team task 创建独立 git worktree，worker 和 verifier 绑定同一 workspace，失败时保留 worktree 供排查 |
| Eval Harness | `eval smoke/replay/compare/learn` | golden case、replay artifact、回归对比和 eval-driven candidate learning 形成可重复反馈源 |

污染防护规则：

- `candidate` experience 不进入上下文。
- `rejected` experience 不进入上下文。
- 只有 `VERIFIED` experience 会作为 `verified_experience` 被 `/context` 和 prompt context 召回。
- `promote` 不会自动发生，必须人工执行 `/experience promote <id>`。
- `eval learn` 只生成 candidate，不自动 verify、不自动 promote。
- `/team run <task> --worktree [--verify]` 会为 task 创建 `.workspaces/<safe-slug>` 隔离工作区；不会自动 merge、commit 或 cleanup，后续通过 `/workspace diff <id>`、`/change create`、`/team report <taskId>` 收口。

完整演示脚本见 [docs/demo/self-improving-agent-loop.md](docs/demo/self-improving-agent-loop.md)。最小流程示例见 [examples/context_engineering_flow.md](examples/context_engineering_flow.md)、[examples/approval_and_diffreview.md](examples/approval_and_diffreview.md)、[examples/experience_learning_flow.md](examples/experience_learning_flow.md)、[examples/eval_learning_flow.md](examples/eval_learning_flow.md)。

## 3. 核心能力总览

以下内容按“模块 -> 能力 -> 当前状态”组织，尽量以代码事实为准。

### 3.1 接入入口

| 模块 | 能力 | 当前状态 | 说明 |
| --- | --- | --- | --- |
| CLI | `agent` 单次运行 | 已支持 | 支持 `--message/-m`、`--session/-s`、`--config/-c`、`--workspace/-w` |
| CLI | `agent` 交互模式 | 已支持 | 无 `--message` 时进入 REPL，支持流式输出 |
| CLI | `serve` 服务模式 | 已支持 | 会启动 `AgentLoop`、`ChannelManager`、`HeartbeatService`、OpenAI 兼容 API |
| CLI | `eval` / `status` / `tools` / `skills` / `provider login` / `onboard` | 已支持 | `eval` 会运行 JSONL 场景并生成可回放 artifact；`provider login` 对 OAuth 型 Provider 仅给提示，不做浏览器登录 |
| HTTP API | `POST /v1/chat/completions` | 已支持 | OpenAI 风格接口，支持 `session_id` |
| HTTP API | `GET /v1/models` / `GET /health` | 已支持 | 便于接入客户端与健康检查 |
| HTTP API | 流式响应 | 已支持 | 当前实现为 SSE 风格 `stream=true` |
| 多渠道入口 | WebSocket Server | 已支持 | 内建 WebSocket 服务端，可作为桥接入口 |
| 多渠道入口 | QQ | 已支持 | 含网关连接、文本/附件处理、出站上传 |
| 多渠道入口 | Weixin | 已支持 | 轮询收发、状态持久化、上下文 token/typing 维护 |
| 多渠道入口 | Email | 已支持 | IMAP 轮询 + SMTP 回复 |
| 多渠道入口 | Feishu / DingTalk | 部分支持 | 已支持出站 HTTP、公开 webhook server 和文本入站标准化；加密事件仍为预留能力 |
| 多渠道入口 | WeCom | 部分支持 | 出站可发；公开 webhook server 支持 token 校验与文本入站，复杂加密回调仍为预留能力 |

### 3.2 Agent 运行时

| 模块 | 能力 | 当前状态 | 说明 |
| --- | --- | --- | --- |
| `MessageBus` | 统一入站/出站消息队列 | 已支持 | 入口层与核心处理解耦 |
| `AgentLoop` | 会话串行化、并发门控、后台任务调度 | 已支持 | 同一会话串行处理，支持全局并发限制 |
| `AgentRunner` | LLM 调用与 Tool Calling 循环 | 已支持 | 支持串行/并发工具执行、流式回调、checkpoint |
| `ContextBuilder` | system prompt / runtime context / history 组装 | 已支持 | 支持图片内容块，带运行时标签 |
| `ContextSelectionService` | 分层上下文选择与相关性召回 | 已支持 | 拆分 `recent_history / task_state / user_profile / memory_recall / tool_trace` |
| `CommandRouter` | Slash 命令 | 已支持 | 当前已注册 `/new`、`/stop`、`/help`、`/status`、`/dream*` |
| Checkpoint | 运行时断点与恢复 | 已支持 | 使用会话元数据保存工具循环中间态 |
| 自动补救 | 工具循环超限后二次放宽重试 | 已支持 | 非流式下会对某些 `tool_loop` 场景自动扩大迭代次数重试 |

### 3.3 LLM Provider

| 模块 | 能力 | 当前状态 | 说明 |
| --- | --- | --- | --- |
| `ProviderRegistry` | 多 Provider 规格注册 | 已支持 | 包含 OpenAI、Anthropic、Azure OpenAI、DashScope、DeepSeek、OpenRouter、Ollama 等 |
| `ProviderFactory` | 按模型推断 Provider 并实例化 | 已支持 | 默认按模型关键字 / `api_base` 进行启发式推断 |
| `OpenAICompatProvider` | OpenAI 兼容 Chat/Stream/Tool Calls | 已支持 | 当前最完整的 Provider 实现 |
| `AnthropicProvider` | Anthropic 调用 | 已支持 | 已接入 Provider 工厂 |
| `AzureOpenAIProvider` | Azure OpenAI 调用 | 已支持 | 已接入 Provider 工厂 |
| OAuth Provider | `openai_codex` / `github_copilot` 登录 | 部分支持 | 注册表有定义，但 CLI `provider login` 只提示“未内置 OAuth 流程” |

### 3.4 Tool / Function Calling

| 模块 | 能力 | 当前状态 | 说明 |
| --- | --- | --- | --- |
| `ToolRegistry` | 工具注册、Schema 导出、参数校验 | 已支持 | 内置工具与 `mcp_` 工具统一管理 |
| 文件系统工具 | `read_file` / `list_dir` / `write_file` / `edit_file` / `notebook_edit` | 已支持 | 路径越界校验、读后编辑保护已接入 |
| 搜索工具 | `glob` / `grep` | 已支持 | 适合代码与文档检索 |
| 命令工具 | `exec` | 已支持 | 有超时、危险命令过滤、SSRF 检查、工作区限制 |
| Web 工具 | `web_fetch` / `web_search` | 已支持 | 带 SSRF 防护；`web_search` 支持 DuckDuckGo、Tavily、SearXNG、Jina、Brave、Kagi |
| 调度工具 | `cron` | 已支持 | 支持 `add/list/remove/enable/disable/run/status` |
| 子代理工具 | `spawn` | 已支持 | 异步后台子代理，完成后回灌主流程 |

### 3.5 Session / Memory / Dream / Cron / Heartbeat

| 模块 | 能力 | 当前状态 | 说明 |
| --- | --- | --- | --- |
| `SessionManager` | 会话 JSONL 落盘 | 已支持 | 文件位于工作区 `sessions/` |
| `Consolidator` | 上下文逼近窗口时归档旧消息 | 已支持 | 归档摘要写入 `memory/history.jsonl` |
| `MemoryStore` | 结构化记忆 + Markdown 兼容视图 | 已支持 | 主存储为 `memory/memory_entries.jsonl`，兼容输出 `MEMORY.md / USER.md / SOUL.md` |
| `Dream` | 结构化记忆维护器 | 已支持 | 从历史提取候选记忆并做合并去重，支持 `/dream`、`/dream-log`、`/dream-restore` |
| `task_state` | 任务状态机 | 已支持 | 会话内维护 `goal / current_step / blocked_reason / next_action`，`/status` 可见 |
| `GitStore` | Dream 版本快照 | 已支持 | 用于记忆文件回溯与恢复 |
| `CronService` | 定时任务调度 | 已支持 | 支持 `at/every/cron` 三类计划 |
| `HeartbeatService` | 定期任务与通知 | 已支持 | `serve` 模式下会启动 |

### 3.6 MCP 接入

| 模块 | 能力 | 当前状态 | 说明 |
| --- | --- | --- | --- |
| `MCPLoader` | MCP server 统一加载/卸载 | 已支持 | 会在 `AgentLoop` 启动后台装载 |
| `MCPAdapters` | MCP tool/resource/prompt 包装为 Tool | 已支持 | 命名统一为 `mcp_<server>_*` |
| `stdio` 传输 | 本地命令型 MCP server | 已支持 | 适合本地工具进程 |
| `sse` 传输 | SSE MCP server | 已支持 | 自动建立 SSE 监听并等待 endpoint |
| `streamableHttp` 传输 | JSON-RPC over HTTP 风格 MCP | 部分支持 | 代码已有轻量实现，但当前没有测试覆盖，兼容性取决于服务端 |
| `enabled_tools` 过滤 | 只暴露部分 MCP 工具 | 已支持 | 同时支持原始名与包装后名 |

### 3.7 安全与稳健性

| 模块 | 能力 | 当前状态 | 说明 |
| --- | --- | --- | --- |
| 网络安全 | SSRF / 私网目标拦截 | 已支持 | `web_*` 与 `exec` 都会复用 |
| 文件安全 | 路径规范化、工作区限制、路径越界拦截 | 已支持 | `restrictToWorkspace` 生效 |
| 命令安全 | 危险命令过滤、路径穿越拦截、URL 检查 | 已支持 | `exec` 默认受控 |
| 发送稳健性 | 渠道消息重试、简单熔断 | 已支持 | Feishu/DingTalk/WeCom/QQ 等出站均有重试/熔断封装 |
| 流式传输 | 增量消息合并 | 已支持 | `ChannelManager` 会对连续 delta 做合并 |
| 会话隔离 | API session 锁 / 会话串行化 | 已支持 | 避免同一会话并发写入错乱 |

### 3.8 当前限制与未闭环点

| 模块 | 当前状态 | 说明 |
| --- | --- | --- |
| Feishu / DingTalk / WeCom 入站 | 当前部分支持 | 已内置 `/webhook/feishu`、`/webhook/dingtalk`、`/webhook/wecom` 文本入站闭环；附件、语音和完整加密回调仍未展开 |
| `streamableHttp` MCP | 当前部分支持 | 已补基础 JSON-RPC over HTTP 集成测试；仍需覆盖更多 MCP 规范兼容场景 |
| 配置/注释历史包袱 | 基本清理 | 仍保留少量旧变量/旧目录 fallback 以兼容已有用户配置 |

## 4. 项目架构

Ricbot 的结构可以理解为“应用组装层 + 领域主链路 + 外部集成层 + 基础设施层 + 工具层”。

### 4.1 分层职责

- `app`
  - 启动与命令入口层
  - 负责装配 `Config`、`Provider`、`AgentLoop`、`ChannelManager`
- `domain`
  - Agent 核心主链路与领域对象
  - 包含 `AgentLoop`、`AgentRunner`、`Session`、`Memory`、`Skill`、`Subagent`
- `integration`
  - 第三方协议/外部系统适配层
  - 包含 LLM Provider、OpenAI 兼容 API、MCP、各类 Channel
- `tool`
  - 供模型调用的工具层
  - 文件、搜索、命令、Web、Cron、Spawn 等都在这里
- `infra`
  - 通用基础设施能力
  - 配置、安全、Cron、Git、Heartbeat、模板、重试、熔断等

### 4.2 主链路

```text
用户输入
  │
  ├─ CLI: agent / serve
  ├─ HTTP: /v1/chat/completions
  └─ Channel: QQ / WebSocket / Email / Weixin / ...
  │
  ▼
InboundMessage
  │
  ▼
MessageBus
  │
  ▼
AgentLoop
  │  ├─ 会话锁 / 并发门控
  │  ├─ SessionManager
  │  ├─ Consolidator / MemoryStore / Dream
  │  ├─ SkillsLoader / SkillRouter
  │  ├─ ContextBuilder
  │  └─ AgentRunner
  │
  ▼
LLMProvider
  │
  ├─ 直接返回 assistant 内容
  └─ 返回 tool_calls
        │
        ▼
     ToolRegistry
        │
        ├─ builtin tools
        └─ mcp_* tools
        │
        ▼
     tool result -> 回到 AgentRunner 继续迭代
  │
  ▼
OutboundMessage
  │
  ├─ CLI 渲染
  ├─ API 封装为 OpenAI 风格响应 / SSE chunk
  └─ ChannelManager 分发到各渠道
```

### 4.3 关键链路说明

1. 入口层先把输入转换为 `InboundMessage`。
2. `MessageBus` 负责缓冲与解耦。
3. `AgentLoop` 负责会话、上下文、技能、记忆、调度与工具上下文准备。
4. `AgentRunner` 负责模型调用和工具循环。
5. `ToolRegistry` 负责 builtin tools 与 MCP tools 的统一暴露。
6. 结果再回到 `MessageBus` 或同步返回给 API/CLI。

## 5. 目录结构说明

```text
Ricbot/
├─ src/main/java/ricbot/
│  ├─ app/
│  │  ├─ bootstrap/        # 程序入口与组件装配
│  │  └─ cli/              # CLI 命令、交互模式、onboard
│  ├─ domain/
│  │  ├─ agent/            # AgentLoop / AgentRunner / ContextBuilder
│  │  ├─ message/          # InboundMessage / OutboundMessage / MessageBus
│  │  ├─ session/          # Session / SessionManager
│  │  ├─ memory/           # MemoryStore / Consolidator / Dream
│  │  ├─ skill/            # SkillsLoader / SkillRouter
│  │  └─ subagent/         # SubagentManager
│  ├─ integration/
│  │  ├─ api/              # OpenAI 兼容 API
│  │  ├─ llm/              # Provider 抽象与具体实现
│  │  ├─ mcp/              # MCP Loader / Adapter / Transport
│  │  ├─ channel/          # QQ / 微信 / 飞书 / 邮件 / WebSocket 等
│  │  └─ command/          # Slash 命令路由
│  ├─ tool/
│  │  ├─ api/              # Tool 抽象与 ToolRegistry
│  │  ├─ filesystem/       # 文件系统工具
│  │  ├─ process/          # exec / spawn
│  │  ├─ search/           # glob / grep
│  │  ├─ web/              # web_fetch / web_search
│  │  └─ cron/             # cron tool
│  └─ infra/
│     ├─ config/           # 配置模型与加载器
│     ├─ cron/             # 定时任务基础设施
│     ├─ security/         # SSRF 与网络安全
│     ├─ heartbeat/        # Heartbeat 服务
│     ├─ git/              # GitStore
│     ├─ fs/               # 路径/显示工具
│     ├─ runtime/          # 运行时辅助
│     └─ template/         # Prompt 模板
├─ src/main/resources/
│  ├─ skills/              # 内置技能
│  └─ templates/           # Prompt 模板
├─ src/test/java/          # 测试
├─ config/ricbot.config.json
├─ bin/ricbot              # Unix 启动脚本
├─ bin/ricbot.cmd          # Windows 启动脚本
├─ ricbot                  # 根目录包装脚本
└─ pom.xml
```

### 重点说明

- 启动层：`app/bootstrap`、`app/cli`
- 核心逻辑：`domain/*`
- 工具层：`tool/*`
- 渠道层：`integration/channel/*`
- 外部集成：`integration/api`、`integration/llm`、`integration/mcp`
- 基础设施：`infra/*`

> 测试目录里仍能看到 `core/`、`transport/` 等旧命名，这反映的是迁移过程中的历史层次，而非当前主包结构。

## 6. 快速入门

### 6.1 环境要求

- JDK：17
- 构建工具：Maven 3.9+，或直接使用仓库内 `mvnw`
- 操作系统：macOS / Linux / Windows 均可，命令示例以下优先使用 Unix Shell
- 外部依赖：
  - 至少一个可用的 LLM Provider API Key
  - 如需 Web 工具、MCP、渠道功能，还需要对应网络与第三方服务

补充说明：

- 当前仓库里 `mvnw` 可能没有执行位，保险起见建议使用 `sh ./mvnw`
- 如果启用 `exec.sandbox=true`，还需要本机具备 `sandbox-exec` 或 `bwrap`

### 6.2 获取项目

```bash
git clone <your-repo-url>
cd Ricbot
```

### 6.3 配置项目

#### 配置文件优先级

1. CLI `--config/-c`
2. 环境变量 `RICBOT_CONFIG`
3. JVM 属性 `-Dricbot.config=...`
4. 默认路径 `~/.ricbot/config.json`

仓库内已提供示例配置：

```text
config/ricbot.config.json
```

#### 最小可运行配置示例

```json
{
  "agents": {
    "defaults": {
      "workspace": "./workspace",
      "model": "qwen-plus",
      "max_tool_iterations": 20,
      "max_tool_result_chars": 10000,
      "timezone": "Asia/Shanghai"
    }
  },
  "providers": {
    "openai": {
      "api_key": "${RICBOT_API_KEY}",
      "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
  },
  "tools": {
    "restrictToWorkspace": true,
    "web": {
      "enable": false
    },
    "exec": {
      "enable": true,
      "timeout": 60,
      "sandbox": false,
      "allowed_env_keys": []
    }
  }
}
```

#### 环境变量

```bash
export RICBOT_API_KEY="your-api-key"
```

说明：

- 配置中的 `${RICBOT_API_KEY}` 会在启动时严格解析
- 如果引用了未设置的环境变量，启动会直接报错
- 当前示例里虽然模型为 `qwen-plus`，但配置写在 `providers.openai` 节点下，这是一种“OpenAI 兼容网关配置方式”；Ricbot 会按模型与 `api_base` 自动推断 Provider

### 6.4 启动方式

#### 1. 命令行单次运行

```bash
sh ./mvnw -q -DskipTests package
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json \
  -m "你好，请介绍一下你的能力"
```

#### 2. 命令行交互运行

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json
```

#### 3. `serve` 模式启动

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

#### 4. fat-jar 启动

```bash
sh ./mvnw -q -DskipTests package
java -jar target/Ricbot-1.0-SNAPSHOT.jar --version
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent -c config/ricbot.config.json -m "hello"
```

#### 5. 使用包装脚本启动

```bash
sh ./ricbot agent -c config/ricbot.config.json -m "hello"
sh ./ricbot serve -c config/ricbot.config.json
```

#### 6. 运行评测场景

`eval` 子命令用于从 harness 角度做可重复回归。场景文件是 JSONL，每行一个 case：

```json
{"id":"hello","input":"请用一句话问候我","expected_contains":["你好"]}
{"id":"no-error","input":"列出当前目录","expected_not_contains":["Exception","Traceback"]}
{"id":"read-only","input":"只分析当前目录，不要写文件","allowed_side_effects":"none","max_file_changes":0}
{"id":"fixture","input":"读取 docs/input.txt","clean_workspace":true,"workspace_files":{"docs/input.txt":"hello fixture\n"},"expected_tools":["read_file"],"expected_file_contains":{"docs/input.txt":["hello fixture"]}}
{"id":"multi-turn","turns":[{"input":"记住代号 alpha","expected_contains":["已记录"]},{"input":"我刚才给你的代号是什么？","expected_contains":["alpha"]}]}
{"id":"json","input":"返回 JSON","expected_json_required":["status"],"expected_json_values":{"status":"ok"},"expected_json_absent":["error"]}
{"id":"known-gap","input":"触发已知缺口","xfail":true,"xfail_reason":"等待工具错误恢复优化","expected_failure_kind":"tool_error","expected_contains":["不会出现"]}
{"id":"future-case","input":"后续补 MCP 故障注入","skip":true,"skip_reason":"fixture 尚未准备好","expected_contains":["不会执行"]}
```

运行：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval \
  --config config/ricbot.config.json \
  --scenarios evals/scenarios.jsonl \
  --out workspace/.ricbot/evals \
  --tag fast \
  --fail-fast
```

不访问真实模型的 CLI smoke：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval lint \
  --scenarios evals/golden.jsonl \
  --out target/eval-lint

java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-smoke-workspace \
  --out target/eval-smoke-artifacts \
  --tag fast \
  --fail-fast

java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-replayable-workspace \
  --out target/eval-replayable-artifacts \
  --tag replayable \
  --fail-fast

java -jar target/Ricbot-1.0-SNAPSHOT.jar eval replay \
  --run target/eval-replayable-artifacts/<run-id> \
  --workspace target/eval-replay-workspace \
  --out target/eval-replay-artifacts \
  --fail-fast
```

离线回放已有 artifact，不访问真实模型：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval replay \
  --config config/ricbot.config.json \
  --case workspace/.ricbot/evals/<run-id>/cases/hello.json

java -jar target/Ricbot-1.0-SNAPSHOT.jar eval replay \
  --config config/ricbot.config.json \
  --run workspace/.ricbot/evals/<run-id> \
  --fail-fast
```

对比两次 eval run，识别相对 baseline 的回归：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval compare \
  --baseline workspace/.ricbot/evals/<old-run-id> \
  --candidate workspace/.ricbot/evals/<new-run-id> \
  --out workspace/.ricbot/eval-comparisons/<compare-id>
```

输出 artifact：

- `manifest.json`：模型、provider、`provider_mode`、工具 schema hash、workspace 运行前快照；smoke artifact 会标记 `provider_mode=smoke`，replay 会据此自动使用确定性 smoke 环境
- `cases.jsonl`：每个 case 的状态、失败分类、耗时、工具列表、artifact 路径
- `cases/<id>.json`：单 case 的场景、响应、模型请求/响应 `model_calls`、workspace diff、run trace、context trace
- `turn_results`：多轮 case 中每一轮的输入、响应、工具列表与 trace
- `summary.json`：通过/失败计数、`failures_by_kind`、模型/工具调用总数、耗时 p50/p95、token usage 汇总
- `report.md`：面向人工排障的 Markdown 报告，包含失败 case、失败详情、artifact 路径和 replay 命令
- `workspace-after.json`：运行后 workspace 快照

`eval compare` 输出：

- `comparison.json`：baseline/candidate 的 case 状态变化、回归数量、改进数量、summary 指标 delta
- `comparison-report.md`：面向人工审阅的对比报告；当出现 pass -> fail、baseline case 缺失、或新增失败 case 时，CLI 退出码为 2

Eval-driven Learning：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval learn \
  --run workspace/.ricbot/evals/<run-id> \
  --workspace workspace \
  --include-xfail
```

`eval learn` 会读取 eval run artifact，将 failed、regression、unexpected_passed 以及显式 `--include-xfail` / `--include-skipped` 的 case 转成 `experience/candidates.jsonl` 中的 candidate experience。它不会自动 verify、不会 promote 到 notes/project，也不会进入上下文召回，避免把一次失败直接固化成项目规则。

推荐流程：

```bash
ricbot eval smoke --scenarios evals/golden.jsonl --workspace target/eval-smoke-workspace
ricbot eval replay --run workspace/.ricbot/evals/<run-id>
ricbot eval learn --run workspace/.ricbot/evals/<run-id> --workspace workspace
/experience list
/experience verify <id>
/experience promote <id>
```

`eval lint` 会在不访问模型的情况下静态检查场景文件，输出 `lint.json` 和 `lint-report.md`。它会拦截重复 id、缺少输入、没有任何断言/预算/副作用策略、非法 regex、非法 JSON path、负数预算、workspace fixture 路径逃逸等问题。

常用场景字段：

- `expected_contains` / `expected_not_contains` / `expected_regex`：响应断言
- `expected_json_required` / `expected_json_absent` / `expected_json_values`：JSON 响应断言；路径支持 `a.b` 与 `items[0].name`
- `turns`：多轮场景；每轮支持 `input`、`expected_contains`、`expected_not_contains`、`expected_regex`、`expected_json_required`、`expected_json_absent`、`expected_json_values`、`expected_tools`、`forbidden_tools`、`expected_stop_reason`
- `tags`：场景标签；CLI 可用 `--tag fast` 只运行某类场景，可重复传入或用逗号分隔；`--exclude-tag flaky` 可排除标签
- `skip` / `skip_reason`：跳过暂不可执行的 case；跳过会写 artifact 并计入 `skipped`，不会调用模型
- `xfail` / `xfail_reason` / `expected_failure_kind`：标记预期失败；失败时计入 `expected_failed` 且不使 run 失败，意外通过会变成 `xpass` 并计入失败
- `expected_tools` / `forbidden_tools`：要求或禁止某些工具被使用
- `clean_workspace`：运行该 case 前清空工作区普通文件；会保留 `.git`、`.idea`、`.ricbot` 等运行元数据目录。默认只允许清理临时目录、`target` 下目录，或带 `.ricbot-eval-workspace` 标记的目录；确需清理其他目录时显式加 `--allow-unsafe-workspace-clean`
- `restore_workspace`：运行该 case 后恢复工作区普通文件，默认开启；会保留 `.git`、`.idea`、`.ricbot`、`sessions`、`memory` 等运行元数据目录。需要保留文件给人工排查时可在 case 中设为 `false`，或 CLI 使用 `--no-restore-workspace`
- `restore_session`：运行该 case 后恢复当前 eval session，默认开启；需要专门测试跨 case session 泄漏时可设为 `false`，或 CLI 使用 `--no-restore-session`
- `workspace_files`：运行前写入的 fixture 文件，key 为工作区相对路径，value 为文件内容
- `expected_file_contains` / `expected_file_not_contains`：对运行后工作区文件内容做断言
- `expected_session_message_count` / `expected_session_role_counts` / `expected_session_contains` / `expected_session_not_contains`：对当前 case session 的消息数、角色分布和内容做断言
- `expected_memory_counts` / `expected_memory_file_contains` / `expected_memory_file_not_contains`：对 memory 目录计数和文件内容做断言；memory 目前只做状态快照和断言，不默认恢复
- `max_model_calls` / `max_tool_calls`：限制模型轮次和工具调用次数
- `expected_stop_reason`：要求最终 `run_trace.stop_reason` 等于指定值，例如 `stop`
- `allowed_side_effects`：副作用策略；`none` / `read_only` 禁止文件变化和非只读工具；`files` 允许文件写入但不允许 network/process/cron/mcp；如需放行可显式包含 `network`、`process`、`cron`、`mcp`，或用 `any` / `all`
- `max_file_changes`：允许的最大文件变化数，超出会归类为 `side_effect_violation`
- `max_duration_ms`：单 case 最大耗时，超出会归类为 `latency_budget_exceeded`

Replay 相关失败分类：

- `replay_mismatch`：回放输出与原始 artifact 不一致，或回放时的模型请求 messages/tools/model/tool_choice 等与录制 artifact 不一致；workspace 根路径会在请求签名里归一化，避免同一 artifact 换目录回放时误报
- `replay_exhausted`：回放时模型请求次数超过已录制的 `model_calls`
- `replay_artifact_invalid`：case artifact 缺少可回放的模型调用记录

仓库内置 `evals/golden.jsonl`，CI 的 `mvn test` 会通过确定性 provider 跑这组 golden 场景，避免依赖真实模型或网络。
`eval smoke` 会配置内置 stdio fake MCP server，覆盖 MCP tool 注册、调用和 timeout 恢复，不依赖外部 MCP 服务。

Artifact 会对常见敏感 key（例如 `api_key`、`authorization`、`token`、`secret`、`password`）做基础脱敏；真实模型 eval 仍应避免把生产密钥、私密文件内容写入场景或工作区。

说明：

- `ricbot` / `bin/ricbot` 会自动检查并构建最新 fat-jar
- Windows 可使用 `ricbot.cmd` 或 `bin/ricbot.cmd`

#### 7. IDE 调试启动

主类：

```text
ricbot.app.bootstrap.RicbotApplication
```

常见启动参数：

```text
agent -c config/ricbot.config.json -m "hello"
```

或：

```text
serve -c config/ricbot.config.json
```

### 6.5 第一次运行示例

#### 场景

尽快验证 Ricbot 主链路是否能跑通。

#### 操作

```bash
export RICBOT_API_KEY="your-api-key"
sh ./mvnw -q -DskipTests package
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json \
  -m "请用三句话介绍 Ricbot"
```

#### 预期结果

- 终端会先输出当前生效的配置摘要，例如模型、Provider、`api_base`、是否存在 API Key
- 随后输出 `ricbot` 的回答正文
- 首次运行后，工作区通常会逐步生成：
  - `workspace/memory/MEMORY.md`
  - `workspace/USER.md`
  - `workspace/SOUL.md`
  - `workspace/sessions/`
  - `workspace/.ricbot/`

## 7. 详细使用教程

以下内容按“场景 -> 操作 -> 结果”组织。

### 7.1 CLI 模式

#### 场景：执行一次 Agent 调用

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json \
  -s cli:demo \
  -m "请列出当前项目的主要模块"
```

结果：

- 使用会话 `cli:demo`
- 执行一次完整 Agent 回合
- 会话记录会写入工作区 `sessions/`

#### 场景：进入交互模式

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json \
  -s cli:chat
```

结果：

- 进入交互 REPL
- 可直接输入 `/help`、`/status`、`/new`、`/stop`

#### 场景：查看当前状态

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar status
```

结果：

- 输出配置文件是否存在
- 输出工作区路径是否存在
- 输出默认模型

### 7.2 API 模式

#### 场景：启动 OpenAI 兼容 API

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

结果：

- 启动 AgentLoop
- 启动已启用渠道
- 启动 OpenAI 兼容 API
- 同时启动本地只读 Web Console：`http://127.0.0.1:<port>/console`

#### 场景：非流式调用

```bash
curl -X POST http://127.0.0.1:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen-plus",
    "session_id": "demo-api-1",
    "messages": [
      {"role": "user", "content": "请介绍一下这个项目"}
    ]
  }'
```

结果：

- 返回 OpenAI 风格 `chat.completion`
- `session_id` 会映射到内部会话键 `api:<session_id>`

#### 场景：流式调用

```bash
curl -N -X POST http://127.0.0.1:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen-plus",
    "stream": true,
    "session_id": "demo-api-stream",
    "messages": [
      {"role": "user", "content": "请用要点说明 Ricbot 的架构"}
    ]
  }'
```

结果：

- 返回 SSE 数据流
- 最后以 `data: [DONE]` 结束

注意：

- 请求体里的 `model` 必须与服务当前启动模型一致，否则会返回 400
- 当前 `serve` 实际监听端口优先使用 `api.port`；未配置 `api.port` 或值不大于 0 时回退到 `gateway.port`

### 7.3 Web Console

#### 场景：查看本地只读运行概览

启动 `serve` 后打开：

```text
http://127.0.0.1:8000/console
```

Console 提供本地页面和 JSON API：

- `GET /console`
- `GET /console/api/health`
- `GET /console/api/config-doctor`
- `GET /console/api/traces`
- `GET /console/api/team-reports`
- `GET /console/api/workspaces`
- `GET /console/api/experiences`
- `POST /console/api/experiences/<id>/verify`
- `POST /console/api/experiences/<id>/reject`
- `POST /console/api/experiences/<id>/promote-skill`
- `GET /console/api/approvals`
- `POST /console/api/approvals/<id>/approve`
- `POST /console/api/approvals/<id>/reject`
- `GET /console/api/actions`
- `GET /console/api/evals`
- `GET /console/api/evals/<run-id>`

页面展示：

- Config Doctor 状态与 Provider 推断摘要
- Latest trace / run timeline
- Team task reports
- Workspace sessions
- Experience candidates / verified items
- Experience actions：candidate 可人工 `Verify` / `Reject`；verified 可 `Promote Skill` 生成 `skills/generated/*.md`
- Pending approvals：展示待审批请求，可人工 `Approve` / `Reject`
- Recent Console Actions：展示最近 Console 写操作审计记录
- Eval runs：读取 `workspace/.ricbot/evals` 下最近的 run，展示 passed/failed/skipped、`provider_mode`、model、`failures_by_kind`，展开后查看失败 case 和转义后的 `report.md`

安全说明：

- Console 不提供 workspace discard、change create、team run、eval run 等高风险写操作
- 当前写操作只开放 experience candidate/skill promotion 和 approval approve/reject 两类人工确认动作
- 页面上的写操作都使用 `POST`，会弹出浏览器确认框；如果配置了 `api.bearer_token`，Console POST 同样要求 Bearer 鉴权
- Console 写操作会追加审计到 `workspace/.ricbot/console-actions.jsonl`，记录 action、target、结果、来源地址、时间和 message；审计写入失败不会阻断主操作，但会返回 warning
- Console POST 会做 CSRF-lite 检查：如果请求带 `Origin` 或 `Referer`，必须来自本机 Console origin；缺失时允许 CLI/curl 场景并记录 warning
- Console POST 有轻量内存限流：同一 remote address + action 在 10 秒内最多 20 次，超限返回 429
- Console 不支持执行 shell、丢弃 worktree、创建 change、运行 team task 或启动 eval
- Console 不支持从页面启动 eval，只读读取已有 artifact
- Console API 不输出真实 API key，也不允许任意路径读取
- Approval 列表、action result 和 audit record 会对 api_key/token/secret/password/authorization/bearer/cookie/set-cookie 等字段脱敏；不要把生产密钥放入 approval args
- Eval viewer 只读取当前 workspace 内的 `.ricbot/evals`，`run-id` 会做路径穿越校验；manifest 中疑似 key/token/secret/password 的字段会降级为 `[REDACTED]`
- `report.md` 在页面中按文本转义展示，不会作为 HTML 注入；但 eval artifact 仍可能包含模型输出和本地路径，分享前请自行确认敏感信息
- 默认 API 监听是 `127.0.0.1`；不要把 Console 暴露到公网
- 如果你把 `api.host` 配成 `0.0.0.0`，`serve` 会要求 `api.bearer_token`，启动日志也会提示 Console 随 API 暴露的风险
- 本轮没有 WebSocket 实时推送，页面通过 HTTP 拉取数据

### 7.4 WebSocket / Channel 模式

#### 场景：把 Ricbot 当作 WebSocket 服务端

配置示例：

```json
{
  "channels": {
    "websocket": {
      "enabled": true,
      "host": "127.0.0.1",
      "port": 8765,
      "path": "/ws",
      "token_issue_path": "/issue-token",
      "token_issue_secret": "demo-secret",
      "websocket_requires_token": true,
      "streaming": true,
      "allow_from": ["*"]
    }
  }
}
```

启动：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve -c config/ricbot.config.json
```

获取临时 token：

```bash
curl http://127.0.0.1:8765/issue-token \
  -H "Authorization: Bearer demo-secret"
```

连接：

```text
ws://127.0.0.1:8765/ws?client_id=demo-client&token=<issued-token>
```

结果：

- 连接建立后，每个 `client_id` 对应一个独立会话
- 出站消息可按 `message` / `delta` 两种类型推送

### 7.5 Tools 怎么启用

#### 场景：只启用文件与命令工具

```json
{
  "tools": {
    "restrictToWorkspace": true,
    "web": {
      "enable": false
    },
    "exec": {
      "enable": true,
      "timeout": 60,
      "sandbox": false
    }
  }
}
```

结果：

- 内置工具至少会包含 `read_file`、`list_dir`、`write_file`、`edit_file`、`glob`、`grep`
- `exec` 只有在 `tools.exec.enable=true` 时才会注册

#### 场景：查看当前已启用工具

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar tools \
  --config config/ricbot.config.json
```

结果：

- 输出当前工作区
- 输出 `restrictToWorkspace` 与 `exec` 实际配置
- 列出当前 `tools` 子命令会枚举的核心本地工具与 MCP 工具

注意：

- 这个子命令当前不是 `AgentLoop` 的完整运行时工具快照
- 运行时额外注册的 `notebook_edit`、`cron`、`spawn`、`web_*` 不一定都会在这里显示

### 7.6 `web` / `exec` / `mcp_servers` 怎么配置

#### Web 工具

```json
{
  "tools": {
    "web": {
      "enable": true,
      "search": {
        "provider": "duckduckgo",
        "max_results": 5,
        "timeout": 10
      }
    }
  }
}
```

结果：

- 注册 `web_fetch` 与 `web_search`
- `web_fetch` 会做 SSRF 校验并优先尝试 Jina/可读性提取
- `web_search` 可切换多个搜索 Provider

补充：

- `web.max_chars` 字段在配置模型里存在，但当前 `ConfigLoader` 尚未把它从 JSON 映射回运行配置

#### Exec 工具

```json
{
  "tools": {
    "restrictToWorkspace": true,
    "exec": {
      "enable": true,
      "timeout": 60,
      "sandbox": true,
      "allowed_env_keys": ["PATH", "JAVA_HOME"]
    }
  }
}
```

结果：

- 命令执行默认仍受工作区与安全过滤约束
- 若 `sandbox=true`，运行时会尝试使用 `sandbox-exec` 或 `bwrap`

注意：

- 这不是容器级隔离，只是轻量级本地沙箱包装
- 若系统未安装所需命令，会报 sandbox 不可用

#### MCP 服务器

`stdio` 示例：

```json
{
  "tools": {
    "mcp_servers": {
      "local-docs": {
        "type": "stdio",
        "command": "node",
        "args": ["./mcp-server.js"],
        "tool_timeout": 60,
        "enabled_tools": ["*"]
      }
    }
  }
}
```

`sse` 示例：

```json
{
  "tools": {
    "mcp_servers": {
      "remote-search": {
        "type": "sse",
        "url": "https://example.com/sse",
        "tool_timeout": 60
      }
    }
  }
}
```

`streamableHttp` 示例：

```json
{
  "tools": {
    "mcp_servers": {
      "remote-http": {
        "type": "streamableHttp",
        "url": "https://example.com/mcp",
        "tool_timeout": 60
      }
    }
  }
}
```

### 7.7 Config Doctor 启动前诊断

#### 场景：检查配置是否真的生效

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json
```

也可以输出机器可读 JSON：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json \
  --json
```

输出会包含：

- 配置文件路径、工作区、默认模型、推断 Provider、`api_base`
- API key 是否已解析；不会输出真实 key
- `web` / `exec` / `mcp` 启用状态，以及 `restrictToWorkspace`
- `gateway.port`、`api.port` 与实际 API 监听端口
- Provider capability 的轻量静态推断
- ignored / reserved / partially-supported 字段
- warnings 与 suggested fixes

当前诊断覆盖的典型问题：

- `${ENV_NAME}` 引用的环境变量不存在
- Provider API key 缺失或仍是未解析占位符
- `api.port` 与 `gateway.port` 不一致
- `tools.web.max_chars` 写入 JSON 但当前不会映射到运行时
- `exec.sandbox=true` 但本机没有 `sandbox-exec` / `bwrap`
- `tools.web.search.provider` 需要 key/base URL 但未配置
- MCP server `type` 未知
- `restrictToWorkspace=false` 的安全风险
- Provider `api_base` 为空或模型/Provider 推断不明确

Provider capability 是静态元数据与启发式结果，无法确认时会以 `UNKNOWN` 或 `-1` 降级。运行时只会参考“明确为 false”的能力做保守降级，例如不向不支持 tool calling 的模型暴露 tools、把不支持 streaming 的请求降级为非流式、拒绝明确不支持 vision 的图片输入；`UNKNOWN` 不阻断运行，会保留原行为并写入运行事件 warning。用户仍应通过显式 provider/model、`api_base` 与配置覆盖来修正能力推断。

运行时能力策略当前范围：

- `supportsToolCalling=false`：不暴露 tools，不进入工具调用循环；如果模型仍返回 tool calls，会返回清晰降级提示
- `supportsToolCalling=UNKNOWN`：保持原工具调用行为，并记录 `capability_warning`
- `supportsStreaming=false`：流式请求自动降级为普通 chat 请求
- `supportsStreaming=UNKNOWN`：保持原流式行为，并记录 `capability_warning`
- `supportsVision=false`：检测到图片内容块时直接提示当前模型不支持图片输入
- `contextWindowTokens > 0`：估算上下文接近窗口阈值时记录 warning，提醒优先使用现有压缩/裁剪链路

### 7.8 MCP 工具怎么接入

#### 场景：接入一个本地 stdio MCP

操作：

1. 在配置中添加 `tools.mcp_servers`
2. 启动 `agent` 或 `serve`
3. 运行 `tools` 命令检查是否出现 `mcp_<server>_*`

结果：

- 工具会被包装成 Ricbot 内部 Tool
- 资源与 Prompt 也会被包装成只读 `mcp_*` 工具

### 7.9 QQ / 微信 / WebSocket 等渠道接入

#### QQ 渠道

```json
{
  "channels": {
    "qq": {
      "enabled": true,
      "app_id": "your-app-id",
      "secret": "your-secret",
      "allow_from": ["*"],
      "media_dir": "./workspace/.ricbot/media/qq"
    }
  }
}
```

结果：

- 启动后会连接 QQ Gateway
- 文本与附件会转换成统一 `InboundMessage`

#### Weixin 渠道

```json
{
  "channels": {
    "weixin": {
      "enabled": true,
      "allow_from": ["*"],
      "base_url": "https://ilinkai.weixin.qq.com"
    }
  }
}
```

结果：

- 启动长轮询
- token 与状态会持久化

#### Feishu / DingTalk / WeCom

`serve` 会复用同一个 API server 暴露企业 IM 入站 webhook：

- `POST /webhook/feishu`
- `POST /webhook/dingtalk`
- `POST /webhook/wecom`

当前入站支持范围：

- 文本消息会标准化为 `InboundMessage` 并进入现有 `MessageBus` / `AgentLoop`
- 会话键稳定为 `feishu:<chatId>`、`dingtalk:<conversationId>`、`wecom:<roomId or externalUserId>`
- 图片、附件、语音等非文本消息本轮只返回 success + unsupported 提示，不会投递给 Agent
- 基于 `platform + eventId` 做 5 分钟内存去重，重复回调直接返回 success，不重复投递

配置示例：

```json
{
  "channels": {
    "feishu": {
      "enabled": true,
      "appId": "cli_xxx",
      "appSecret": "${FEISHU_APP_SECRET}",
      "webhookToken": "${FEISHU_WEBHOOK_TOKEN}",
      "encryptKey": "",
      "allowFrom": ["*"]
    },
    "dingtalk": {
      "enabled": true,
      "appKey": "dingxxx",
      "appSecret": "${DINGTALK_APP_SECRET}",
      "webhookSecret": "${DINGTALK_WEBHOOK_SECRET}",
      "allowFrom": ["*"]
    },
    "wecom": {
      "enabled": true,
      "botId": "bot_xxx",
      "secret": "${WECOM_SECRET}",
      "token": "${WECOM_WEBHOOK_TOKEN}",
      "allowFrom": ["*"]
    }
  }
}
```

平台校验说明：

- Feishu：支持 URL verification challenge；配置 `webhookToken` 后会校验事件中的 `token`。`encryptKey` 为预留字段，本轮不做加密事件解密。
- DingTalk：配置 `webhookSecret` 后校验 `timestamp + "\n" + secret` 的 HMAC-SHA256 + Base64 签名，兼容 query/header 传入 `timestamp` 和 `sign`；未配置时仅做开发模式解析。
- WeCom：配置 `token` 后校验 query/body 中的 token；`msg_signature` 加密回调本轮为预留能力。

安全说明：

- webhook endpoint 不复用 Console Bearer auth，而使用平台 token/sign 校验
- 请求体上限为 1 MiB；JSON 解析失败返回 400
- raw event 进入 metadata 前会脱敏 token、secret、signature、authorization、cookie 等字段
- 生产环境不要把 webhook 暴露给公网时省略平台 token/sign 配置
- 如果没有启用对应出站 channel，入站仍会投递到 MessageBus，响应会标记 `outboundConfigured=false`

### 7.10 Session / Memory / Dream / Cron / Heartbeat 怎么工作

#### Session

- 每个会话会落盘到工作区 `sessions/*.jsonl`
- API 会话键形如 `api:<session_id>`
- WebSocket 会话键形如 `websocket:<client_id>`

#### Memory

- 长期记忆文件：
  - `workspace/memory/MEMORY.md`
  - `workspace/USER.md`
  - `workspace/SOUL.md`
- 历史归档：
  - `workspace/memory/history.jsonl`

#### Dream

- 可通过 `/dream` 手动触发
- 可通过 `/dream-log` 查看最近记忆提交
- 可通过 `/dream-restore <sha>` 恢复某次记忆快照

#### Cron

- 通过 `cron` 工具管理
- 存储文件位于 `workspace/.ricbot/cron/store.json`

#### Heartbeat

- `serve` 模式下根据 `gateway.heartbeat.enabled` 启动
- 当前更偏后台执行/通知能力

### 7.11 常见命令

#### CLI 命令

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar --version
java -jar target/Ricbot-1.0-SNAPSHOT.jar status
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve --config config/ricbot.config.json
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor --config config/ricbot.config.json
java -jar target/Ricbot-1.0-SNAPSHOT.jar tools --config config/ricbot.config.json
java -jar target/Ricbot-1.0-SNAPSHOT.jar skills --config config/ricbot.config.json
java -jar target/Ricbot-1.0-SNAPSHOT.jar provider login openai
```

#### 会话内命令

```text
/new
/stop
/help
/status
/dream
/dream-log
/dream-restore <commit_sha>
```

### 7.12 如何排查 MCP 是否连接成功

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar tools --config config/ricbot.config.json
```

排查思路：

1. 看输出里是否出现 `mcp_<server>_...`
2. 如果没有，先确认 `tools.mcp_servers` 是否成功加载
3. 再确认 `enabled_tools` 是否把工具过滤掉了
4. 对 `sse` 检查 URL 是否真的以 SSE 端点提供服务
5. 对 `streamableHttp` 检查服务端是否支持同步 JSON-RPC 风格调用

### 7.13 如何查看日志

常见日志位置：

```text
<workspace>/.ricbot/logs/ricbot.log
```

如果启动失败，终端会打印实际日志路径。建议同时查看：

- 终端 stderr
- `ricbot.log`
- 工作区中的 `sessions/`、`memory/`、`.ricbot/cron/`

## 8. 配置说明

本节重点说明哪些配置已真正生效，哪些仍偏预留。

### 8.1 `agents.defaults`

示例：

```json
{
  "agents": {
    "defaults": {
      "workspace": "./workspace",
      "model": "qwen-plus",
      "max_tool_iterations": 20,
      "context_window_tokens": 64000,
      "context_block_limit": 200,
      "max_tool_result_chars": 16000,
      "provider_retry_mode": "standard",
      "timezone": "Asia/Shanghai",
      "unified_session": false,
      "disabled_skills": [],
      "session_ttl_minutes": 0,
      "dream": {
        "enabled": true,
        "model_override": "",
        "max_batch_size": 20,
        "max_iterations": 5,
        "cron": "0 3 * * *"
      }
    }
  }
}
```

字段说明：

| 字段 | 是否生效 | 说明 |
| --- | --- | --- |
| `workspace` | 已生效 | 工作区根目录 |
| `model` | 已生效 | 默认模型 |
| `max_tool_iterations` | 已生效 | Agent 工具循环上限 |
| `context_window_tokens` | 已生效 | 影响 Consolidator 裁剪 |
| `context_block_limit` | 已生效 | 透传给 `AgentRunSpec` |
| `max_tool_result_chars` | 已生效 | 限制工具结果进入上下文的长度 |
| `provider_retry_mode` | 已生效 | 控制是否使用 Provider 重试 |
| `timezone` | 已生效 | 注入运行时上下文、Cron 默认时区 |
| `unified_session` | 已生效 | 是否统一会话键 |
| `disabled_skills` | 已生效 | SkillsLoader 会过滤 |
| `session_ttl_minutes` | 已生效 | 触发 AutoCompact 扫描 |
| `dream.enabled` | 已生效 | 控制 Dream 是否启动 |
| `dream.cron` | 已生效 | 后台 Dream 调度通过 CronService 计算下一次运行时间 |

### 8.2 `providers`

示例：

```json
{
  "providers": {
    "openai": {
      "api_key": "${RICBOT_API_KEY}",
      "api_base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "extra_headers": {}
    },
    "anthropic": {
      "api_key": "${ANTHROPIC_API_KEY}",
      "api_base": "https://api.anthropic.com"
    }
  }
}
```

说明：

- 已生效：`api_key`、`api_base`、`extra_headers`
- 已生效：按模型/前缀自动推断 Provider
- 建议：生产上尽量显式管理 Provider 配置，不完全依赖自动推断
- 当前限制：OAuth 型 Provider 注册了规格，但 CLI 不负责完成 OAuth 登录

### 8.3 `tools`

示例：

```json
{
  "tools": {
    "restrictToWorkspace": true,
    "ssrf_whitelist": [],
    "web": {
      "enable": true,
      "proxy": "",
      "search": {
        "provider": "duckduckgo",
        "api_key": "",
        "base_url": "",
        "max_results": 5,
        "timeout": 10
      }
    },
    "exec": {
      "enable": true,
      "timeout": 60,
      "sandbox": false,
      "path_append": "",
      "allowed_env_keys": []
    },
    "mcp_servers": {}
  }
}
```

说明：

| 字段 | 是否生效 | 说明 |
| --- | --- | --- |
| `restrictToWorkspace` | 已生效 | 文件与命令工作目录限制 |
| `ssrf_whitelist` | 已生效 | 配置到 `NetworkSecurity` |
| `web.enable` | 已生效 | 控制运行时是否注册 `web_fetch` / `web_search` |
| `web.max_chars` | 当前未完全生效 | 配置模型存在，但 `ConfigLoader` 当前未把该字段从 JSON 映射回来 |
| `web.search.*` | 已生效 | 搜索 Provider 与请求参数 |
| `exec.enable` | 已生效 | 控制 `exec` 注册 |
| `exec.sandbox` | 部分支持 | 依赖本机 `sandbox-exec` 或 `bwrap` |
| `mcp_servers` | 已生效 | 传入 `MCPLoader` 装载 |

### 8.4 `channels`

说明：

- `send_progress`、`send_tool_hints` 已生效，决定是否向渠道发送进度消息与工具提示
- `transcription_provider` 已生效，用于音频转写 Provider 选择
- 各渠道子段是否完全生效，取决于对应渠道当前实现成熟度

### 8.5 `gateway / api`

示例：

```json
{
  "gateway": {
    "port": 8000,
    "heartbeat": {
      "enabled": true,
      "interval_s": 60,
      "keep_recent_messages": 20
    }
  },
  "api": {
    "host": "127.0.0.1",
    "port": 8080,
    "timeout": 120.0
  }
}
```

说明：

| 字段 | 是否生效 | 说明 |
| --- | --- | --- |
| `gateway.port` | 已生效 | `api.port` 未配置或不大于 0 时作为 `serve` API 监听端口 |
| `gateway.heartbeat.*` | 已生效 | `HeartbeatService` 使用 |
| `api.host` | 已生效 | `serve` 启动 OpenAI-compatible API 时使用 |
| `api.port` | 已生效 | 大于 0 时覆盖 `gateway.port` 作为 API 监听端口 |
| `api.timeout` | 已生效 | 转换为毫秒后传给 API server |
| `api.bearer_token` | 已生效 | 配置后用于保护 OpenAI-compatible API |

## 9. 功能模块详解

### 9.1 `AgentLoop`

- 作用：Ricbot 的核心编排器
- 核心职责：
  - 消费 `MessageBus` 入站消息
  - 串行化同会话处理
  - 调用 Session、Memory、Skill、Tool、Dream、Cron、Subagent
  - 把结果封装成 `OutboundMessage`
- 当前实现情况：
  - 已支持会话锁、并发门控、后台 Dream/Cron、slash 命令、checkpoint 恢复
- 说明：
  - Dream 调度会读取 `dream.cron`，无效或空 cron 会回退到默认间隔

### 9.2 `AgentRunner`

- 作用：模型调用与工具循环执行器
- 核心职责：
  - 调用 `chat` / `chatWithRetry` / `chatStream`
  - 处理 `tool_calls`
  - 支持并发工具执行
  - 把工具结果回灌消息序列
- 当前实现情况：
  - 是主链路里最关键的“推理-调用工具-继续推理”执行器
- 当前限制：
  - 更复杂的策略控制仍偏轻量，尚未形成插件式策略系统

### 9.3 `ContextBuilder`

- 作用：构建发送给模型的消息数组
- 核心职责：
  - 注入 system prompt
  - 注入 runtime context（时间、时区、渠道、chat_id）
  - 拼接历史消息、当前用户消息、媒体块
- 当前实现情况：
  - 已支持图片内联与工具消息合法性保护
- 当前限制：
  - Prompt 拼装策略仍以模板与约定为主，尚未抽象成多 Persona 体系

### 9.4 `ToolRegistry`

- 作用：统一工具管理
- 核心职责：
  - 注册与注销工具
  - 输出 OpenAI function schema
  - 参数校验与执行分派
- 当前实现情况：
  - 内置工具与 `mcp_` 工具统一输出
- 当前限制：
  - 分派仍基于类型判断与通用 `execute`，后续可继续解耦

### 9.5 `SessionManager`

- 作用：会话落盘与读取
- 核心职责：
  - `getOrCreate`
  - JSONL 保存与加载
  - 简要枚举会话列表
- 当前实现情况：
  - 已支持原子写入与旧会话迁移路径兼容
- 当前限制：
  - 当前为文件存储，未接入数据库型索引

### 9.6 `MemoryStore` / `Consolidator` / `Dream`

- 作用：长期记忆与上下文压缩
- 核心职责：
  - 维护 `MEMORY.md`、`USER.md`、`SOUL.md`
  - 归档超长历史
  - 利用 LLM 整理长期记忆
- 当前实现情况：
  - 已能形成“短期会话 -> history.jsonl -> Dream -> Git 快照”的链路
- 当前限制：
  - Dream 调度与策略还比较固定

### 9.7 `SkillsLoader` / `SkillRouter`

- 作用：技能发现与动态加载
- 核心职责：
  - 发现内置技能和工作区技能
  - 根据上下文打分选择技能
  - 渲染技能文档注入到上下文
- 当前实现情况：
  - 已支持 frontmatter、优先级、关键词、渠道、工具提示等评分
- 当前限制：
  - 当前更偏文档式技能，不是代码插件式技能

### 9.8 `SubagentManager`

- 作用：后台子代理运行器
- 核心职责：
  - 接受 `spawn` 请求
  - 创建自己的 ToolRegistry 与 Context
  - 后台执行后把结果通知主链路
- 当前实现情况：
  - 已支持异步任务、会话级取消、专用线程池
- 当前限制：
  - 当前仍是单机内线程池子代理，不是分布式 Agent Pool

### 9.9 `MCPLoader` / `MCPAdapters` / `MCPTransportFactory`

- 作用：MCP 对接层
- 核心职责：
  - 解析配置
  - 建立 stdio / sse / streamableHttp 连接
  - 将 tool/resource/prompt 包装成 Ricbot 工具
- 当前实现情况：
  - 已支持 `enabled_tools` 过滤与热重载式重连基础能力
- 当前限制：
  - `streamableHttp` 缺少测试覆盖；兼容性需实际服务端验证

### 9.10 `LLMProvider` / `OpenAICompatProvider` / `ProviderFactory`

- 作用：模型提供者抽象
- 核心职责：
  - 统一聊天接口
  - 统一流式接口
  - 统一错误包装与重试策略
- 当前实现情况：
  - OpenAI 兼容 Provider 最完善
- 当前限制：
  - 不同 Provider 的高级特性还未完全对齐

### 9.11 `ChannelManager` / `BaseChannel` / 各渠道

- 作用：渠道接入与消息分发
- 核心职责：
  - 初始化启用渠道
  - 消费出站消息并分发
  - 做流式合并、重试与基本鉴权
- 当前实现情况：
  - 渠道整体框架已搭好，多数渠道已有独立实现
- 当前限制：
  - 不同渠道成熟度不一致，尤其是入站链路

### 9.12 关键工具

| 工具 | 作用 | 当前实现情况 | 当前限制 |
| --- | --- | --- | --- |
| `read_file` / `list_dir` / `write_file` / `edit_file` | 文件操作 | 已支持 | `edit_file` 依赖先读后改的保护策略 |
| `notebook_edit` | Jupyter Notebook 单元格编辑 | 已支持 | 只针对 `.ipynb` |
| `glob` / `grep` | 搜索 | 已支持 | 更偏本地代码与文档搜索 |
| `exec` | Shell 命令执行 | 已支持 | 安全策略较严格，sandbox 为轻量实现 |
| `web_fetch` / `web_search` | Web 获取/检索 | 已支持 | 受 SSRF 与外网可达性约束 |
| `cron` | 定时任务管理 | 已支持 | 当前为本地单机调度 |
| `spawn` | 子代理任务 | 已支持 | 单机后台线程池 |
| `mcp_*` | MCP 能力桥接 | 已支持 | 依赖对应 MCP server 健康状态 |

## 10. 典型场景示例

### 10.1 用 CLI 跑一次 Agent

场景说明：本地快速验证主链路。

命令：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json \
  -m "请总结当前项目的亮点"
```

预期效果：

- Agent 返回一段文本
- 会话与记忆目录按需生成

### 10.2 启动 API 服务

场景说明：让外部客户端以 OpenAI SDK 方式调用 Ricbot。

命令：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

预期效果：

- `http://127.0.0.1:8000/health` 返回 `{"status":"ok"}`

### 10.3 接入一个 MCP 工具

场景说明：将外部 MCP server 接成模型可调用工具。

配置：

```json
{
  "tools": {
    "mcp_servers": {
      "demo-mcp": {
        "type": "stdio",
        "command": "node",
        "args": ["./demo-mcp.js"]
      }
    }
  }
}
```

命令：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar tools \
  --config config/ricbot.config.json
```

预期效果：

- 出现 `mcp_demo-mcp_*` 命名的工具

### 10.4 打开 WebSocket 作为桥接入口

场景说明：让前端或桥接服务通过 WebSocket 与 Ricbot 对话。

命令：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve -c config/ricbot.config.json
```

预期效果：

- WebSocket 客户端建立连接后可发送文本消息
- Agent 回复会按 message/delta 回推

### 10.5 启用 QQ 渠道

场景说明：接入 QQ 机器人。

配置：

```json
{
  "channels": {
    "qq": {
      "enabled": true,
      "app_id": "xxx",
      "secret": "xxx",
      "allow_from": ["*"]
    }
  }
}
```

预期效果：

- `serve` 启动后打印 `QQ 机器人已启动`

### 10.6 查看当前工具列表

场景说明：确认哪些工具真的注册成功。

命令：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar tools \
  --config config/ricbot.config.json
```

预期效果：

- 输出 `read_file`、`list_dir`、`glob`、`grep`、`exec` 等
- 若 MCP 可用，也会出现 `mcp_` 前缀工具
- 这是当前 CLI 可见工具子集，不等于 `AgentLoop` 的完整运行时工具表

### 10.7 排查 MCP 工具是否连上

场景说明：配置了 MCP 但模型没有调用。

命令：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar tools \
  --config config/ricbot.config.json
```

预期效果：

- 若没有 `mcp_` 工具，说明还没成功注册
- 进一步检查配置键、连接方式、服务可用性与日志

## 11. 常见问题（FAQ）

### 11.1 为什么配置了 MCP 但没有调用？

常见原因：

- MCP server 没连上，导致 `mcp_` 工具根本没注册
- `enabled_tools` 配错，把目标工具过滤掉了
- 模型当前回合没有判断出需要该工具
- 配置用了错误的传输类型

建议先跑：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar tools --config config/ricbot.config.json
```

### 11.2 为什么 `tools` 里看不到 `mcp_` 工具？

优先排查：

1. `tools.mcp_servers` 是否真的被加载
2. `type` / `url` / `command` 是否正确
3. `enabled_tools` 是否误过滤
4. 当前环境是否能访问目标服务

### 11.3 `streamableHttp` 为什么不能用？

当前代码中：

- 已有 `streamableHttp` 轻量实现
- 但没有测试覆盖，也不是完整流式语义适配

如果你的服务端本质上提供的是 SSE 端点，建议显式写：

```json
{ "type": "sse", "url": "https://.../sse" }
```

### 11.4 为什么 exec sandbox 开了反而不能执行？

因为 `sandbox=true` 只是启用轻量沙箱包装，仍依赖系统命令：

- macOS：`sandbox-exec`
- Linux：`bwrap`

如果系统里没有这些命令，`exec` 会直接报 sandbox 不可用。

### 11.5 为什么某些渠道能注册但功能不完整？

因为不同渠道成熟度不同：

- Feishu / DingTalk 当前偏出站模式
- WeCom 当前入站依赖额外代理
- WebSocket、QQ、Weixin 相对完整

### 11.6 启动失败常见原因有哪些？

- 环境变量未设置，`${VAR}` 解析失败
- API Key 缺失
- `api.port` 或回退使用的 `gateway.port` 被占用
- `mvnw` / 脚本没有执行权限
- 目标 Provider 或外部服务不可达

### 11.7 环境变量没生效怎么办？

检查：

1. 配置文件里是否确实写成 `${ENV_NAME}`
2. 是否在同一个 shell 会话里 `export`
3. 是否使用了 `--config` 指向正确配置
4. 运行 `config doctor -c config/ricbot.config.json` 查看缺失的环境变量与 suggested fixes

### 11.8 IDEA / Git / Maven 构建异常如何排查？

- IDE 主类选 `ricbot.app.bootstrap.RicbotApplication`
- Maven 优先使用 `sh ./mvnw -q -DskipTests package`
- 仓库当前可能存在未提交改动，避免误以为是构建产物导致

### 11.9 `api.port` 和 `gateway.port` 谁决定监听端口？

当前 `serve` 的实际规则是：

1. `api.port > 0` 时监听 `api.port`
2. 否则回退监听 `gateway.port`

如果两者配置不同，`config doctor` 会在 effective config 里显示 `actual.listen`，并提示端口差异。

## 12. 当前限制与后续规划

### 当前限制

- 已可用主链路：
  - CLI
  - AgentLoop
  - OpenAI 兼容 API
  - Tool Calling
  - Session / Memory / Dream / Cron
  - MCP 基础接入
- 半成品或部分支持能力：
  - Feishu / DingTalk / WeCom 入站公开服务接入
  - `streamableHttp` MCP 的完整规范兼容性验证
  - OAuth 型 Provider 登录

### 后续规划建议

1. 为 `streamableHttp`、渠道接入补测试
2. 完成剩余历史兼容入口的迁移策略
3. 增加 Web 控制台或管理页
4. 增加更细粒度的 Tool 权限策略与审计

## 13. License / Contributing

### License

仓库当前未看到明确的 License 文件。

如果你准备公开发布，建议尽快补充：

- `MIT`
- `Apache-2.0`
- 或团队内部约定的专有许可证

在 License 未明确前，外部使用与分发边界应谨慎处理。

### Contributing

如果你要继续扩展这个项目，建议优先从以下方向提交改进：

- 补齐 `api.*` 配置真实接线
- 为 MCP / Channel 增加集成测试
- 清理历史命名与注释
- 统一 README、配置示例与当前代码行为

建议提交流程：

1. 新增或修复对应测试
2. 明确标注“已支持 / 部分支持 / 预留能力”
3. 更新 `README.md` 与 `config/ricbot.config.json` 示例
