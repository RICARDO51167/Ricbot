# ricbot 项目总文档（架构与使用指南 / ARCHITECTURE_AND_USAGE）

> 项目：ricbot  
> 定位：具备 CLI、AgentLoop、LLM Provider、多工具调用、Session、Memory、Skill、Subagent、MCP、Web/API、Channel、多种基础设施能力的智能 Agent 系统  
> 语言/构建：Java 17 + Maven（shade 打包可执行 fat-jar）  
> 适用读者：第一次接触的开发者 / 维护者 / 交接与汇报 / 面试讲解

---

## 目录

1. [项目简介](#1-项目简介)
2. [项目整体架构](#2-项目整体架构)
3. [项目主链路](#3-项目主链路)
4. [目录结构详解](#4-目录结构详解)
5. [核心模块详解](#5-核心模块详解)
6. [配置系统详解](#6-配置系统详解)
7. [启动与使用方式](#7-启动与使用方式)
8. [OpenAI 兼容 API 使用说明](#8-openai-兼容-api-使用说明)
9. [Session / Memory / Cron / Heartbeat 的运行机制](#9-session--memory--cron--heartbeat-的运行机制)
10. [安全与稳定性设计](#10-安全与稳定性设计)
11. [已完成能力与未完成能力](#11-当前项目的已完成能力与未完成能力)
12. [后续优化建议](#12-后续优化建议)
13. [给新开发者的阅读顺序建议](#13-给新开发者的阅读顺序建议)

---

## 1. 项目简介

### 1.1 ricbot 是什么

ricbot 是一个“可运行的智能 Agent 系统骨架 + 已落地的关键主链路”，目标是在一个统一框架下完成：

- 多入口接入：CLI、HTTP(OpenAI 兼容)、多种即时通讯 Channel（飞书/钉钉/企微/微信/QQ/Email/WebSocket 等）。
- 统一中枢：用 MessageBus 解耦“输入接入”和“Agent 核心处理”。
- AgentLoop：核心调度引擎，负责 Session、Memory、Skills、Tools、Cron、Subagent、MCP 等协作。
- LLM Provider：抽象多家模型（OpenAI 兼容、Anthropic、Azure OpenAI），并提供标准重试框架。
- Tools：以 OpenAI function-calling schema 暴露工具；支持文件系统、搜索、进程执行、Web 抓取/搜索、Cron、Subagent spawn、MCP 工具桥接。
- Session：会话落盘与并发锁保证，同会话串行处理、支持中断恢复（checkpoint）。
- Memory：包含长期记忆文件（MEMORY.md/USER.md/SOUL.md）、归档 history.jsonl、Dream 长期整理与 Git 版本化。
- MCP：Model Context Protocol 的最小可用接入（stdio / sse），把 MCP tool/resource/prompt 包装成 ricbot 工具。

入口类与关键代码参考：
- [RicbotApplication.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/bootstrap/RicbotApplication.java)（程序入口：直接转到 CLI）
- [CliCommands.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/cli/CliCommands.java)
- [AgentLoop.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentLoop.java)
- [RicbotApiServer.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/api/RicbotApiServer.java)

### 1.2 解决什么问题

ricbot 解决的是“把大模型能力做成可工程化、可扩展、可接入生产环境形态”的系统化问题：

- 把输入（来自 CLI/API/IM）统一转换成可处理的 InboundMessage。
- 把 Agent 的执行变成可观测、可中断、可恢复、可持久化的循环（AgentRunner + Hook + checkpoint + Session）。
- 把外部能力（文件/命令/网络/MCP）变成可控、可限制、可审计的 Tools。
- 把长期偏好与人格等沉淀到 Memory，并通过 Dream 反复更新、可回滚（GitStore）。

### 1.3 核心能力清单（按系统视角）

- **AgentLoop 主循环与调度**：并发门控、会话锁、后台任务（Dream、Cron、AutoCompact）。
- **工具调用**：ToolRegistry 统一注册、schema 导出、参数校验、并发执行与结果结构化封装。
- **多渠道**：ChannelManager 统一启动、出站消息派发、发送重试、流式增量合并。
- **OpenAI 兼容 API**：/v1/chat/completions /v1/models /health，带 session_id 会话隔离。
- **长期记忆与归档**：Consolidator（会话归档到 history.jsonl）、Dream（更新 MEMORY/USER/SOUL 并 Git 提交）。
- **Subagent**：SpawnTool + SubagentManager，后台执行并把结果回灌主链路。
- **MCP**：MCPLoader + MCPAdapters + MCPTransportFactory，把 MCP server 的能力挂为工具。
- **安全**：SSRF 防护（NetworkSecurity）、路径越界防护（FileToolSupport/ExecTool）、重试与熔断（RetryUtils/CircuitBreaker）。

### 1.4 成熟度评估（诚实描述）

结论：**“可运行骨架 + 主链路可用 + 多模块已落地，但存在明显的占位/半成品点”**。

已经较完整可用的部分：
- CLI 入口、AgentLoop 主链路、Session 落盘、ToolRegistry/工具调用循环、Web/Exec/FS/搜索工具、安全防护、CronService/CronTool、Dream/GitStore、OpenAI 兼容 API（非流式）。

明显的占位/未完全闭环点：
- API server 明确不支持 stream（/v1/chat/completions 里直接拒绝 stream=true）。
- MCP 的 streamableHttp 传输未实现；stdio/sse 可用但仍属于“轻量实现/适配层”。
- ExecTool 的 sandbox 选项在当前版本是“开启即拒绝执行”（不是真正隔离沙箱）。
- DreamConfig（agents.defaults.dream）存在，但 AgentLoop 实际是固定每 15 分钟跑一次 Dream，并未读取该配置进行启停/调度。
- Provider 的 OAuth login 在 CLI 中是占位实现（provider login 只打印提示）。
- GitStore.diffCommits 是占位实现（返回空字符串）。

---

## 2. 项目整体架构

ricbot 的包结构更接近“分层 + 领域模块拼装”的风格。你可以用如下分层来理解依赖方向：

### 2.1 分层与依赖方向
┌──────────────────────────────────────────────────────────┐
│ app（应用层）                                             │
│  - CLI/Bootstrap：组装 Config/Provider/AgentLoop/Channel   │
└───────────────┬──────────────────────────────────────────┘
│ 只依赖 domain / infra / integration / tool
┌───────────────▼──────────────────────────────────────────┐
│ domain（核心域）                                          │
│  - AgentLoop/Runner/ContextBuilder                         │
│  - Session/Message/Memory/Skill/Subagent                    │
└───────────────┬──────────────────────────────────────────┘
│ 依赖 infra（通用能力）+ integration（外部对接）+ tool（工具）
┌───────────────▼──────────────────────────────────────────┐
│ infra（基础设施）                                         │
│  - config/cron/fs/git/heartbeat/runtime/security/template   │
│  - retry/circuit breaker 等                                 │
└───────────────┬──────────────────────────────────────────┘
│
┌───────────────▼──────────────────────────────────────────┐
│ integration（集成层）                                     │
│  - llm providers（OpenAICompat/Anthropic/Azure）            │
│  - api（OpenAI 兼容 HTTP server）                           │
│  - channel（IM/WebSocket 等）                               │
│  - mcp（MCP 适配与传输）                                   │
└───────────────┬──────────────────────────────────────────┘
│
┌───────────────▼──────────────────────────────────────────┐
│ tool（工具层）                                            │
│  - Tool 抽象/ToolRegistry                                  │
│  - filesystem/process/search/web/cron/mcp wrappers          │
└──────────────────────────────────────────────────────────┘

核心依赖原则（从代码现状归纳）：
- **app 负责“组装”**，不要放业务逻辑。例：[Bootstrapper.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/bootstrap/Bootstrapper.java) 创建 Provider/AgentLoop/ChannelManager/HeartbeatService。
- **domain 负责“主链路与领域对象”**，例如 AgentLoop 内部组合 Session/Memory/Skills/Tools/Cron/Subagent。
- **infra 负责“跨领域的基础能力”**：配置、路径、安全、模板、cron 计算、重试、熔断。
- **integration 负责“外部协议与第三方系统适配”**：LLM 提供商、MCP、HTTP API、各种 Channel。
- **tool 负责“被 LLM 调用的可执行能力”**，它既是 domain 的“插件”也是 integration 的“桥”。

---

## 3. 项目主链路

这一节把“用户输入进入系统后怎么流转”讲透，并覆盖 CLI / API / Channel 三种入口如何汇聚到 AgentLoop，再经 AgentRunner/LLMProvider/Tools 输出结果。

### 3.1 主链路总览（文本链路图）
[用户输入]
│
├─ CLI: ricbot agent/serve → CliCommands.publishInbound(...)
│
├─ API: POST /v1/chat/completions → RicbotApiServer → AgentLoop.processDirect(...)
│
└─ Channel: BaseChannel.handleMessage/publishEvent → MessageBus.publishInbound(...)
│
▼
MessageBus(inbound queue)
│  AgentLoop.run() consumeInbound()
▼
AgentLoop.dispatch()
│  session lock + concurrency gate
▼
AgentLoop.processMessage()
│
├─ SessionManager.getOrCreate + AutoCompact.prepareSession
├─ Consolidator.maybeConsolidateByTokens(session)
├─ restoreRuntimeCheckpoint / restorePendingUserTurn
├─ CommandRouter（/stop /new /dream...）
├─ ContextBuilder.buildMessages(history + runtime + templates)
├─ MemoryStore.getMemoryContext()
├─ SkillsLoader.getSkillsContext()
├─ SkillRouter.selectAndRender(...)
└─ AgentRunner.run(spec)
│
├─ LLMProvider.chat / chatWithRetry / chatStream
├─ tool_calls → ToolRegistry.execute(...) (并发/串行)
├─ Hook（流式增量、工具提示、checkpoint）
└─ 迭代直到 finish 或 maxIterations
│
├─ saveTurn(session, newMessages)
├─ clear checkpoint/pending flags
└─ SessionManager.save(session)
│
▼
OutboundMessage（content + metadata）
│
├─ CLI：CliCommands.pollOutbound(...) 渲染输出
└─ ChannelManager.dispatchOutboundLoop → channel.send/sendDelta

相关实现：
- Agent 主循环：[AgentLoop.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentLoop.java)
- Runner 工具循环：[AgentRunner.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentRunner.java)
- MessageBus：[MessageBus.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/message/MessageBus.java)
- API server：[RicbotApiServer.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/api/RicbotApiServer.java)
- Channel 出站分发：[ChannelManager.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/ChannelManager.java)

### 3.2 CLI 入口如何进入主链路

CLI 主入口是 [CliCommands.main](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/cli/CliCommands.java)，核心流程：

1. **loadRuntimeConfig + resolveAndPrintEffectiveConfig**
    - 支持 `--config/-c` 指定配置路径；支持 `--workspace/-w` 覆盖工作区。
    - 解析 `${ENV}` 占位符（如果缺失会抛出异常，尤其是 API Key）。
    - 打印生效 model/provider/api_base/key 是否解析（写到 stderr）。

2. **Bootstrapper 组装核心组件**
    - provider = ProviderFactory.makeProvider(config)
    - agentLoop = new AgentLoop(...)

3. **单条消息模式（--message）**
    - 构造 InboundMessage，设置 `_wants_stream=true`，发布到 bus。
    - CLI 轮询 outbound：识别 `_stream_delta`/`_stream_end`/`_streamed`/`_progress` 等元数据并渲染。

4. **交互模式（无 --message）**
    - agentLoop.start() 后循环读取 stdin。
    - 每条输入发布 inbound，持续 poll outbound 输出。

CLI 的“流式输出”并不是 API 的 HTTP stream，而是 **AgentLoop Hook 把 LLM stream delta 转成 outbound delta 消息**，CLI/Channel 再负责渲染/发送。

### 3.3 API 入口如何进入主链路

API 入口是 [RicbotApiServer](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/api/RicbotApiServer.java)，它本质上是“OpenAI 风格协议 → AgentLoop.processDirect 的同步调用桥”。

核心特点：
- 只支持：
    - `POST /v1/chat/completions`
    - `GET /v1/models`
    - `GET /health`
- **明确不支持 stream**：如果请求体 `stream: true`，直接返回 400。
- 对外“模型名”是固定的：启动时传入 `modelName`，请求里的 `model` 必须等于它，否则 400。
- `session_id` 会映射为 `sessionKey = "api:" + session_id`，用于会话隔离。
- 使用 `ReentrantLock` 做 API 层的 session 锁，避免同一 session 并发写 session 文件造成乱序。

对接主链路方式：
- 解析 OpenAI messages → 提取最后 user 内容作为当前输入
- 如 messages 包含历史（或包含非 user role），可选择把 history 写入 Session（shouldSyncHistory）
- 然后调用：`agentLoop.processDirect(userContent, sessionKey, "api", API_CHAT_ID)`
- 返回内容包装成 OpenAI chat completion 响应体

### 3.4 Channel 入口如何进入主链路

Channel 入口由 BaseChannel 抽象统一规范（参考 [BaseChannel.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/BaseChannel.java)）：

- Channel 收到外部消息后调用 `handleMessage(...)` 或 `publishEvent(ChannelEvent)`。
- 这两者最终都会构造 `InboundMessage`，并 `bus.publishInbound(msg)`。
- ChannelEvent（如 IncomingMessageEvent/CommandEvent）统一转换为 InboundMessage，并带 `_event_type/_event_id` 等 metadata（参考 [ChannelEvent.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/event/ChannelEvent.java)）。

出站链路由 ChannelManager 统一消费 bus.outbound 并发送：
- 合并连续 `_stream_delta`（减少刷屏）
- 根据配置选择是否发送 `_progress` 和 `_tool_hint`
- 重试发送（1s/2s/4s，次数由 config 控制）

### 3.5 AgentLoop / ContextBuilder / AgentRunner / LLMProvider / ToolRegistry 如何协作

用一次完整回合来解释各模块职责边界：

1. **AgentLoop 负责“编排”**
    - 选择会话键、加会话锁（同会话串行）
    - SessionManager 取 session
    - Consolidator/AutoCompact 做会话裁剪与归档
    - Memory/Skill 构建额外上下文
    - 调用 ContextBuilder 拼装 messages
    - 构建 AgentRunSpec 并交给 AgentRunner

2. **ContextBuilder 负责“把系统 prompt + runtime context + history + current user message 组装成 LLM messages”**
    - runtime context 使用 `[RUNTIME_CONTEXT] ... [/RUNTIME_CONTEXT]` 标记
    - system prompt 默认来自模板 `templates/agent/identity.md`（见 [PromptTemplates.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/template/PromptTemplates.java)）
    - 支持 media（图片）转成 OpenAI content blocks（data URL 内联，限制 2MB）
    - 对 tool message 的“合法起点”有校验逻辑，避免 tool_call_id 不匹配

3. **AgentRunner 负责“LLM ↔ Tools 的迭代循环”**
    - 每轮调用 provider.chat / chatWithRetry / chatStream
    - 若 LLM 返回 tool_calls：执行 ToolRegistry.execute
        - 可并发执行（spec.concurrentTools=true 时用线程池）
    - 把 tool 执行结果封成 role=tool 的 message，加入 messages
    - 支持 hook：beforeIteration/afterIteration/beforeExecuteTools/onStream/onStreamEnd/finalizeContent
    - 达到 maxIterations 输出 maxIterationsMessage（并对原因做分类）

4. **LLMProvider 负责“对接具体模型与错误重试策略”**
    - 基类提供 retry 框架、错误类型识别、sanitizeEmptyContent 等
    - OpenAICompatProvider 支持 /chat/completions 和流式 SSE 消费（但仍依赖对端是否标准实现）

5. **ToolRegistry 负责“工具注册 + schema 输出 + 参数校验 + 执行”**
    - 把 Tool 转成 OpenAI function schema
    - prepareCall 校验参数是否 object，是否缺必填
    - execute 对内置工具做类型分派（避免反射/兼容不同签名）

---

## 4. 目录结构详解

下面按你给出的结构逐块解释“做什么、位置、协作关系”，并尽量点名关键类。

> 代码根目录：`src/main/java/ricbot`

### 4.1 app

#### 4.1.1 app/bootstrap

职责：**启动组装层**，把配置与核心组件组装起来，尽量不含业务逻辑。

- [RicbotApplication.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/bootstrap/RicbotApplication.java)
    - 程序入口，当前直接委托给 CLI（这意味着“应用形态以 CLI 为主”）。
- [Bootstrapper.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/bootstrap/Bootstrapper.java)
    - loadConfig：支持 `--config` 指定配置文件、`--workspace` 覆盖
    - createProvider：ProviderFactory.makeProvider
    - createAgentLoop：把 config 的参数注入 AgentLoop（含工具开关、MCP server 配置、restrictToWorkspace、unifiedSession、disabledSkills、sessionTtlMinutes 等）
    - createChannelManager / createHeartbeatService：serve 模式需要

协作关系：
- 向下依赖：infra.config（Config/ConfigLoader/RuntimePaths）、integration.llm/provider、domain.agent/MessageBus、integration.channel、infra.heartbeat

#### 4.1.2 app/cli

职责：**CLI 命令行产品层**，包括命令路由、交互式输入输出、onboard 向导等。

- [CliCommands.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/cli/CliCommands.java)
    - onboard/agent/serve/status/provider/tools
    - agent：支持 `--message` 单次调用与交互模式；支持 `--session`；默认 session=cli:direct
    - serve：启动 AgentLoop + ChannelManager + Heartbeat + ApiServer，并阻塞主线程
    - tools：按“安全策略”初始化工具注册表并打印启用工具
- OnboardWizard / StreamRenderer / CliModelHelpers（用于交互体验与输出渲染）

现状提示：
- provider login 是占位实现（打印提示，没有 OAuth 流程）。

### 4.2 domain

domain 是 ricbot 的“核心域”，主链路绝大多数在这里。

#### 4.2.1 domain/agent

职责：**Agent 执行编排 + LLM/Tools 迭代循环 + 上下文构建**。

关键类：
- [AgentLoop.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentLoop.java)
- [AgentRunner.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentRunner.java)
- [AgentRunSpec.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentRunSpec.java)
- [AgentRunResult.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentRunResult.java)
- [ContextBuilder.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/ContextBuilder.java)
- AutoCompact（会话 TTL 自动归档辅助）

协作关系：
- 依赖 Session/Memory/Skill/Subagent/Message/Tool/MCP/Command/Cron/Template/Security

#### 4.2.2 domain/hook

职责：**可插拔的运行生命周期 Hook**，用于流式输出、工具提示、错误回调、finalize 清理等。

- [AgentHook.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/hook/AgentHook.java)
- [AgentHookContext.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/hook/AgentHookContext.java)

#### 4.2.3 domain/memory

职责：**长期记忆与会话归档**。

- [MemoryStore.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/MemoryStore.java)
- [Consolidator.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/Consolidator.java)
- [Dream.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/Dream.java)

#### 4.2.4 domain/message

职责：**消息协议与总线**（解耦入口与核心处理）。

- [InboundMessage.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/message/InboundMessage.java)
- [OutboundMessage.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/message/OutboundMessage.java)
- [MessageBus.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/message/MessageBus.java)

#### 4.2.5 domain/session

职责：**会话对象 + 落盘管理 + 迁移兼容**。

- [Session.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/session/Session.java)
- [SessionManager.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/session/SessionManager.java)

#### 4.2.6 domain/skill

职责：**技能发现、加载、路由与渲染**。

- [SkillsLoader.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/skill/SkillsLoader.java)
- [SkillRoutingContext.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/skill/SkillRoutingContext.java)
- [SkillRouter.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/skill/SkillRouter.java)

#### 4.2.7 domain/subagent

职责：**子代理后台执行与回灌**。

- [SubagentManager.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/subagent/SubagentManager.java)

### 4.3 infra

infra 提供跨模块基础能力。

#### 4.3.1 infra/common

- CircuitBreaker / RetryUtils：WebFetchTool 等使用，提供熔断 + 指数退避。
- HelperUtils：去除 think 标记、truncate、ensureDir 等杂项工具。

#### 4.3.2 infra/config

- [Config.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/config/Config.java)：巨型配置类（如同“配置领域模型”）
- [ConfigLoader.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/config/ConfigLoader.java)：加载/保存/迁移/ENV 解析/SSRF 白名单应用
- [RuntimePaths.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/config/RuntimePaths.java)：~/.ricbot 相关运行路径

#### 4.3.3 infra/cron

- [CronService.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/cron/CronService.java)
- CronTypes / CronExpressionUtils：cron 表达式解析与 next run 计算（基于 cron-utils）

#### 4.3.4 infra/fs

- DisplayPathUtils / FsPathUtils：路径展示与规范化（配合工具提示、路径安全）

#### 4.3.5 infra/git

- [GitStore.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/git/GitStore.java)：Dream 记忆文件版本化（注意 diffCommits 目前占位）

#### 4.3.6 infra/heartbeat

- [HeartbeatService.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/heartbeat/HeartbeatService.java)：读取 HEARTBEAT.md → LLM 决策 → 执行 → 评估 → 通知

#### 4.3.7 infra/runtime

- RuntimeUtils：空响应兜底等
- [RestartSupport.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/runtime/RestartSupport.java)：重启通知 env overlay（与 bin/ricbot 脚本联动）

#### 4.3.8 infra/security

- [NetworkSecurity.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/security/NetworkSecurity.java)：SSRF 防护与 CIDR 白名单

#### 4.3.9 infra/template

- [PromptTemplates.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/template/PromptTemplates.java)：模板加载（classpath 优先，其次 dev 文件系统）
- [ToolHintFormatter.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/template/ToolHintFormatter.java)：把 tool_calls 格式化成“人可读的工具提示”

### 4.4 integration

#### 4.4.1 integration/api

- [RicbotApiServer.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/api/RicbotApiServer.java)
- RuntimeConstants：空回复兜底常量等

#### 4.4.2 integration/channel

- [ChannelManager.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/ChannelManager.java)
- [BaseChannel.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/BaseChannel.java)
- ChannelRegistry：发现 channel（反射/注册表式）
- 各渠道实现：DingTalk/Feishu/Wecom/Weixin/QQ/Email/WebSocket
- WebSocketServer：Java-WebSocket 库封装（WebSocketChannel 可支持 sendDelta）

事件模型：
- [ChannelEvent.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/event/ChannelEvent.java)
- [ChannelEventType.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/event/ChannelEventType.java)
- IncomingMessageEvent / CommandEvent：统一事件载体

#### 4.4.3 integration/command

- CommandRouter：slash 命令分发（AgentLoop 注册 /stop /new /help /status /dream /dream-log /dream-restore 等）

#### 4.4.4 integration/llm

- api：LLMProvider/LLMResponse/GenerationSettings/OpenAIResponsesSupport/ToolCallRequest/TranscriptionProvider 等
- provider：ProviderFactory/ProviderRegistry/ProviderSpec（模型→provider 推断与实例创建）
- openai：OpenAICompatProvider/OpenAITranscriptionProvider
- anthropic：AnthropicProvider
- azure：AzureOpenAIProvider

#### 4.4.5 integration/mcp

- [MCPLoader.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPLoader.java)
- [MCPAdapters.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPAdapters.java)
- [MCPTransportFactory.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPTransportFactory.java)
- [MCPClientSession.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPClientSession.java)
- [MCPServerConnection.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPServerConnection.java)

### 4.5 tools（代码中为 ricbot/tool）

ricbot 的工具系统位于 `ricbot/tool`（注意你给的结构是 tools/，但源码包名是 `ricbot.tool.*`，这是一个“命名需统一”的点：文档/目录与包名存在复数差异）。

- api：Tool / ToolParam / ToolRegistry
- filesystem：ReadFileTool/WriteFileTool/EditFileTool/ListDirTool/NotebookEditTool/FsTool/FileToolSupport
- process：ExecTool/SpawnTool
- search：GlobTool/GrepTool
- web：WebFetchTool/WebSearchTool/WebToolSupport
- cron：CronTool

---

## 5. 核心模块详解

本节按你指定的模块清单逐一“讲清楚职责、关键数据结构、协作关系、当前现状”。

### 5.1 Agent 体系

#### 5.1.1 AgentLoop

代码参考：[AgentLoop.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentLoop.java)

定位：**ricbot 的核心调度引擎**。你可以把它理解为“消息驱动的 Agent 运行时”。

关键职责：
1. **消费 MessageBus.inbound**：run() 循环 poll inbound。
2. **并发治理**：
    - 全局并发门控：`RICBOT_MAX_CONCURRENT_REQUESTS`（Semaphore）
    - 会话串行：`sessionLocks`（每个 sessionKey 一个锁对象）
    - 任务追踪：`activeTasks`（每会话多个 Future，用于 /stop 取消）
3. **主流程编排**：
    - 解析 sessionKey（支持 unifiedSession）
    - SessionManager getOrCreate
    - AutoCompact.prepareSession（按 TTL 做整理）
    - Consolidator.maybeConsolidateByTokens（按 token 预算归档）
    - restoreRuntimeCheckpoint + restorePendingUserTurn（崩溃/中断恢复）
    - Memory + Skills 上下文拼装
    - ContextBuilder.buildMessages（system+history+user）
    - AgentRunner.run(spec)
    - saveTurn + SessionManager.save
4. **命令优先级与控制面**：
    - CommandRouter priority：/stop 优先执行并取消任务
    - 支持 /new /help /status /dream /dream-log /dream-restore
    - /restart 当前禁用（返回“未启用该命令”）
5. **后台任务**：
    - CronService.start()
    - Dream：固定 scheduleWithFixedDelay 每 15 分钟执行一次（注意：这不是配置驱动）
    - AutoCompact sweep：sessionTtlMinutes>0 时每分钟扫描

重要现状点（必须知道）：
- `effectiveSessionKey` 的实现使用 `msg.getSessionKey()`；而在 InboundMessage 构造时通常设置的是 channel/chatId + override。你需要确认 InboundMessage 的 getSessionKey 逻辑（如果它内部用 channel+chatId 生成，则一致；否则可能存在“sessionKeyOverride 与 sessionKey 计算路径”的认知偏差）。在 CLI 中显式设置了 `sessionKeyOverride`，并依赖 `msg.getSessionKey()`。维护者应重点核对该模型是否符合预期。

#### 5.1.2 AgentRunner

代码参考：[AgentRunner.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentRunner.java)

定位：**一次 Agent 执行回合的工具循环引擎**（与 AgentLoop 的“消息驱动调度”不同，它是“LLM 工具迭代内核”）。

核心行为：
- 每轮：
    1. beforeIteration hook
    2. provider.chat / chatWithRetry / chatStream（是否流式由 hook.wantsStreaming 决定）
    3. 把 assistant message（含 tool_calls）追加到 messages
    4. 若无 tool_calls：finalizeContent → stop
    5. 有 tool_calls：beforeExecuteTools hook → 执行工具（并发/串行）→ tool messages 追加 → afterExecuteTools hook
    6. 可选 injectionCallback 注入额外 messages（上限每轮 MAX_INJECTIONS_PER_TURN）

并发工具执行：
- 使用共享线程池 `agent-tools-*`，按 toolCall 顺序归位结果。
- 失败会生成 fallback tool_result。

#### 5.1.3 AgentRunSpec / AgentRunResult

- [AgentRunSpec.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentRunSpec.java)
    - 描述一次 runner.run 的所有参数：initialMessages/tools/model/maxIterations/hook/errorMessage/maxToolResultChars/concurrentTools/providerRetryMode/ checkpointCallback 等。
- [AgentRunResult.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentRunResult.java)
    - 结果：finalContent/messages/toolsUsed/usage/toolEvents/stopReason/error 等。

#### 5.1.4 ContextBuilder

代码参考：[ContextBuilder.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/ContextBuilder.java)

定位：**LLM messages 组装器**（偏“prompt engineering + 结构化输入组织”）。

关键点：
- system prompt：模板 `templates/agent/identity.md` + runtime context
- runtime context：`[RUNTIME_CONTEXT]{now, timezone, channel, chat_id}[/RUNTIME_CONTEXT]`
- history：sanitizeHistory/合法起点修正（避免 tool_call_id 未声明）
- media：支持 image_url block（http/https/data URL 或本地文件内联 base64，限制 2MB）

#### 5.1.5 AgentHook / AgentHookContext

- AgentLoop.buildLoopHook 会构造一个“流式输出 hook”：
    - onStream：把增量发布为 OutboundMessage，并加 `_stream_delta=true`
    - onStreamEnd：发 `_stream_end=true` 的空内容结束标记
    - beforeExecuteTools：发布 `_progress`（思考内容/工具提示）
    - finalizeContent：stripThink（去除思考标记）
- 这使得：
    - CLI 可以消费 outbound 的 delta 来做“伪流式渲染”
    - ChannelManager 可决定是否发送 progress/tool_hint

---

### 5.2 Session / Message

#### 5.2.1 InboundMessage / OutboundMessage / MessageBus

- MessageBus 是两个队列：
    - inbound：Channel/CLI → AgentLoop
    - outbound：AgentLoop → Channel/CLI
- 典型 metadata 约定（来自 AgentLoop hook 与 ChannelManager）：
    - `_wants_stream`：请求是否希望流式
    - `_stream_delta`：流式增量
    - `_stream_end`：流式结束标记
    - `_streamed`：本轮已完成（CLI 用于跳出等待）
    - `_progress`：进度消息
    - `_tool_hint`：进度消息是否为工具提示

#### 5.2.2 Session / SessionManager

- Session 是会话内消息列表（List<Map>）+ metadata + timestamps。
- SessionManager 的落盘格式是 **jsonl**：
    - 第 1 行是 metadata 行（_type=metadata，含 created_at/updated_at/message_count/metadata/last_consolidated）
    - 后续每行是一个 message map（role/content/tool_calls/tool_call_id/name/timestamp 等）
- 文件命名：`safeFilename(key.replace(":", "_")) + "-" + shortHash(key) + ".jsonl"`
    - 这是为了避免 key 太长或包含危险字符，同时用 hash 规避冲突。
- 迁移机制：支持从旧目录/旧命名迁移到新命名（resolveOrMigratePath）。

并发与一致性：
- AgentLoop 层面用 sessionLocks 保证同 session 串行处理。
- API 层面额外用 ReentrantLock 防止并发写入（尤其当多个 HTTP 请求同 session）。

---

### 5.3 Memory 体系

#### 5.3.1 MemoryStore

代码参考：[MemoryStore.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/MemoryStore.java)

定位：**纯文件 I/O 的长期记忆层**，管理以下资产：

- `workspace/memory/MEMORY.md`：长期事实/知识
- `workspace/USER.md`：用户偏好/个人信息（注意隐私）
- `workspace/SOUL.md`：agent 性格/原则/身份设定
- `workspace/memory/history.jsonl`：归档/摘要/原始存档历史
- `workspace/memory/.cursor`：history 的游标（递增）
- `workspace/memory/.dream_cursor`：Dream 已处理到哪条历史
- 旧版迁移：`memory/HISTORY.md` → `history.jsonl`

此外，MemoryStore 内置 GitStore：
- 追踪文件：SOUL.md / USER.md / memory/MEMORY.md
- 用于 Dream 更新后自动提交与回滚（/dream-log /dream-restore）

#### 5.3.2 Consolidator

代码参考：[Consolidator.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/Consolidator.java)

定位：**会话窗口的“压缩器/归档器”**，在上下文 token 逼近限制时，把旧消息归档到 memory/history。

关键机制：
- 估算 token：用字符统计的近似方法（ASCII/非 ASCII 不同权重），再加固定开销。
- 预算：`contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER`
    - 当前 maxCompletionTokens 在 AgentLoop 构造 Consolidator 时写死为 4096（明显是 placeholder，应未来配置化）。
- 归档方式：
    - 将旧消息片段拼成 prompt，渲染 `templates/agent/consolidator_archive.md`，调用 LLM 生成摘要。
    - 摘要写入 history.jsonl；失败时 rawArchive（把原始 messages 记录下来）。

#### 5.3.3 Dream

代码参考：[Dream.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/Dream.java)

定位：**长期记忆整理器**，把 history 中新增片段融合进 MEMORY/USER/SOUL。

流程：
1. 取未处理 history（cursor > dream_cursor）
2. 读取 MEMORY.md/USER.md/SOUL.md 当前内容
3. 渲染 dream 模板（代码里是 `agent/dream.md`，模板资源位于 `src/main/resources/templates/agent/*`）
4. 调用 LLM，让其输出三段：`### MEMORY.md / ### USER.md / ### SOUL.md` 的完整新内容（或输出 `(nothing)`）
5. 写回文件
6. 初始化 Git 仓库（如果未初始化）并 autoCommit
7. dream_cursor 前移

现状与限制：
- Dream 的运行调度目前**由 AgentLoop 固定每 15 分钟触发**，并未读取 Config.DreamConfig 的 enabled/cron。
- GitStore.diffCommits 目前占位，/dream-log 可用，但 show diff 的体验有限。

---

### 5.4 Skill 体系

#### 5.4.1 SkillsLoader

代码参考：[SkillsLoader.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/skill/SkillsLoader.java)

定位：**技能发现与加载器**。

技能来源：
- workspace：`{workspace}/skills/<skillName>/SKILL.md`
- builtin：
    - 开发环境：`src/main/resources/skills`（DEV_BUILTIN_SKILLS_DIR）
    - 打包运行：classpath `resources/skills`

技能文档结构：
- 支持 frontmatter（`--- ... ---`）解析为 Map<String,String>
- body 会去掉 frontmatter 供路由/渲染
- scan cache（2s TTL）减少频繁扫描

#### 5.4.2 SkillRoutingContext / SkillRouter

- SkillRoutingContext 传入：
    - workspace/channel/chatId/message/toolNames/metadata/variables
- SkillRouter 的策略：
    - always=true 的技能必选
    - 其余技能基于：priority + channel match + keyword hits + name match + tool hinted 打分
    - 渲染时支持 `{{variable}}` 替换，并有 maxChars 预算（always 占一半预算）

现状提示：
- SkillRouter 的选择是规则打分（不是 LLM 自己“检索式选择”），优点是确定性强，缺点是需要维护关键词/权重。
- maxSelected/maxChars 由环境变量控制：`RICBOT_SKILLS_MAX_SELECTED` / `RICBOT_SKILLS_MAX_CHARS`。

技能资源参考：
- `src/main/resources/skills/*/SKILL.md`（如 summarize/weather/cron 等）
- `src/main/resources/templates/agent/skills_section.md` 等（用于系统 prompt 组织）

---

### 5.5 Subagent 体系

#### 5.5.1 SubagentManager

代码参考：[SubagentManager.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/subagent/SubagentManager.java)

定位：**后台子代理任务管理器**，用于把耗时/并行任务从主对话中拆出来：

- spawn(task, label, originChannel, originChatId, sessionKey)：
    - 生成 taskId
    - 提交到线程池（队列满会拒绝）
    - 立即返回“已启动”提示
- runSubagent：
    - 构造子代理工具集（Read/Write/Edit/List/Glob/Grep/Exec，可选 Web）
    - 渲染子代理系统提示词模板 `templates/agent/subagent_system.md`（由 PromptTemplates 渲染）
    - runner.run(spec) 执行（maxIterations=15，failOnToolError=true）
    - announceResult：构造 system channel 的 InboundMessage 回灌到 `originChannel:originChatId`

回灌到主链路的本质：
- 子代理并不直接“发给用户”，而是“再走一次主 AgentLoop 的 system 消息处理”，让主 agent 决定如何表达与整合。

#### 5.5.2 SpawnTool

- SpawnTool 是工具层包装，让 LLM 可以调用“spawn 子代理”。
- AgentLoop 会在 exec enabled 时注册 SpawnTool，并通过 setToolContext 注入 channel/chatId，确保回灌路径正确。

---

### 5.6 MCP 体系

#### 5.6.1 MCPLoader

代码参考：[MCPLoader.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPLoader.java)

定位：**MCP 的统一装配入口**：读取 config.tools.mcp_servers → 建立连接 → 将 MCP 能力注册成工具。

关键行为：
- reloadAll：解析 raw map → disconnectAll → connectMcpServers → 注册工具
- stop 时 close：关闭连接并 unregister 工具（按 `mcp_<server>_` 前缀）

#### 5.6.2 MCPAdapters

代码参考：[MCPAdapters.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPAdapters.java)

定位：**协议适配层**，做三件事：
1. parseMcpServers：把弱类型 Map 转成 Config.MCPServerConfig
2. normalizeSchemaForOpenAI：把 MCP schema 规范化为 OpenAI function schema（处理 nullable/oneOf/anyOf 等）
3. Wrapper：
    - MCPToolWrapper：`mcp_<server>_<tool>`
    - MCPResourceWrapper：`mcp_<server>_resource_<name>`（只读）
    - MCPPromptWrapper：`mcp_<server>_prompt_<name>`（只读）

超时与隔离：
- wrapper 每次调用用单线程 executor + Future.get(timeout) 实现超时。
- 这是简单可用方案，但会带来线程频繁创建的成本（未来可优化复用）。

#### 5.6.3 MCPTransportFactory / MCPClientSession / MCPServerConnection

- [MCPTransportFactory.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPTransportFactory.java)
    - 支持：
        - stdio（启动子进程，通过 stdin/stdout JSON-RPC）
        - sse（实现类在同文件后半部分）
    - **streamableHttp：明确未实现**（UnsupportedOperationException）
- [MCPClientSession.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPClientSession.java) 是“占位接口”，未来换官方 SDK 时只要适配成这个接口即可。
- [MCPServerConnection.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPServerConnection.java) 负责提供 session 与 close。

当前限制总结：
- 支持范围：stdio/sse（最小可用），streamableHttp 未落地。
- 工具调用结果主要拼接 text content，对于非文本 block 是 String.valueOf，富内容结构保留有限。
- schema 规范化较实用，但可能仍需要适配不同 MCP server 的非标准 schema。

---

### 5.7 LLM 体系

#### 5.7.1 LLMProvider / LLMResponse / GenerationSettings

- [LLMProvider.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/llm/api/LLMProvider.java)
    - 抽象 chat
    - chatWithRetry：默认 runWithRetry（内置重试延迟 1/2/4 秒，并识别 retryable status）
    - chatStream：默认实现是不真正流式（先 chat 再一次性 onDelta），但 OpenAICompatProvider 覆盖了真正 SSE 消费
    - sanitizeEmptyContent：清洗 message content 为空字符串/缺 text 等情况
- GenerationSettings：temperature/maxTokens/reasoningEffort 等（供 provider 使用）
- LLMResponse：统一承载 content/toolCalls/usage/错误信息（finishReason、errorKind、statusCode、retryAfter 等）

#### 5.7.2 ProviderFactory / ProviderRegistry / ProviderSpec

- [ProviderFactory.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/llm/provider/ProviderFactory.java)
    - 根据 Config + model 推断 providerName
    - 根据 ProviderSpec.backend 创建具体 Provider（openai_compat/anthropic/azure_openai）
- [ProviderRegistry.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/llm/provider/ProviderRegistry.java)
    - 维护 PROVIDERS 列表（openai、anthropic、dashscope、openrouter、deepseek、ollama 等）
    - 支持 findByName/findByModelKeyword/findByKeyPrefix/findByBaseKeyword
- ProviderSpec：描述 provider 的关键字、默认 api_base、是否 local/gateway/direct/oauth、模型 override 等

#### 5.7.3 OpenAICompatProvider / AnthropicProvider / AzureOpenAIProvider

- [OpenAICompatProvider.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/llm/openai/OpenAICompatProvider.java)
    - 走 `/chat/completions`
    - 支持 stream=true，并通过 [OpenAIResponsesSupport.consumeSSE](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/llm/api/OpenAIResponsesSupport.java) 消费 SSE
    - 注意：OpenAIResponsesSupport 对 stream 中 tool_calls 的增量拼装“当前未完全实现”（代码注释明确说明）
- AnthropicProvider / AzureOpenAIProvider：各自实现对应 API（建议维护者在扩展时对齐 LLMResponse 的工具调用语义）

#### 5.7.4 Transcription Providers（Groq/OpenAI）

- [GroqTranscriptionProvider.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/llm/api/GroqTranscriptionProvider.java)
- OpenAITranscriptionProvider（OpenAI whisper）
- BaseChannel 根据 config.channels.transcription_provider 选择 provider，并由 ChannelManager 注入 apiKey/apiBase

现状提示：
- 语音转写能力依赖 Channel 侧是否真的把语音文件落地并调用 transcribeAudio（具体渠道实现需要核查）。

---

### 5.8 Tools 体系

#### 5.8.1 Tool / ToolRegistry

- [Tool.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/api/Tool.java)
    - 定义工具名/描述/参数 schema/只读/独占/执行
- [ToolRegistry.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/api/ToolRegistry.java)
    - register/unregister/get/toolNames
    - getDefinitions：内置工具与 MCP 工具分组排序（MCP 工具名以 `mcp_` 开头）
    - execute：对常用工具做显式分派（ReadFileTool/ListDirTool/ExecTool/GlobTool/GrepTool/WriteFileTool/EditFileTool），其余走 tool.execute

#### 5.8.2 FsTool / SearchToolBase（按“类别”理解）

ricbot 工具并没有显式的 FsToolBase/SearchToolBase 抽象层，而是按包分类 + FileToolSupport/WebToolSupport 公用能力来实现“工具族”。理解时建议用“类别”划分：

- 文件系统类：Read/Write/Edit/List/NotebookEdit/Glob/Grep
- 进程类：Exec/Spawn
- Web 类：WebFetch/WebSearch
- Cron 类：CronTool
- MCP 类：MCPAdapters 中的 wrappers

#### 5.8.3 具体工具职责

文件系统：
- ReadFileTool：读取文件（支持 offset/limit），通常会用 FileToolSupport.ensureAllowed 做路径边界校验
- WriteFileTool：写文件（创建父目录）
- EditFileTool：基于 old_text/new_text 替换
- ListDirTool：列目录
- NotebookEditTool：面向“notebook”类文件的编辑（具体实现需要进一步核查其策略）
- GlobTool / GrepTool：搜索工具（受 allowedDir 限制）

进程：
- [ExecTool.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/process/ExecTool.java)
    - 安全 guard：
        - denyPatterns（危险命令）
        - allowPatterns（白名单模式，可选）
        - SSRF：NetworkSecurity.containsInternalUrl
        - 路径穿越/绝对路径越界（restrictToWorkspace 时）
    - timeout 最大 600s，输出最大 10k 字符
    - **sandbox：当前是“开启即拒绝执行”，并未实现隔离**
- SpawnTool：启动子代理（SubagentManager.spawn）

Web：
- [WebFetchTool.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/web/WebFetchTool.java)
    - SSRF 校验
    - 图片预探测：image/* 则返回 image blocks
    - 优先走 Jina（r.jina.ai）抽取；失败回退简单 readability/text 抽取
    - 内置 CircuitBreaker + RetryUtils
- [WebSearchTool.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/web/WebSearchTool.java)
    - provider 分发（duckduckgo/tavily/searxng/jina/brave/kagi）
    - searxng 会对 baseUrl 做 SSRF 校验

Cron：
- [CronTool.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/cron/CronTool.java)
    - add/list/remove/enable/disable/run/status
    - setContext(channel, chatId)：AgentLoop 注入上下文，cron 的 deliver 回传依赖 payload.channel/chatId

---

### 5.9 API / Channel / 对外接入

#### 5.9.1 RicbotApiServer（命名提醒）

你的清单里出现 “NanobotApiServer / RicbotApiServer”，而代码里是 `RicbotApiServer`。这属于**命名需统一**的典型点：历史迁移中 nanobot → ricbot 的残留较多（类注释里也大量出现 nanobot）。

#### 5.9.2 ChannelManager / BaseChannel / 各渠道

- ChannelManager：
    - 启动所有 enabled channel
    - 消费 outbound 并 dispatch（合并 stream delta、过滤 progress/tool_hint、发送重试）
- BaseChannel：
    - 统一 inbound 构造与 event 去重
    - 统一语音转写 provider 的注入与选择
    - sendDelta 默认空实现（不是所有渠道都支持流式）

各渠道现状：
- WebSocketChannel：相对更完整，支持 streaming outbound、token/allowFrom、token issue route（参考 [WebSocketChannel.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/WebSocketChannel.java)）
- 其他 IM 渠道（飞书/钉钉/企微/微信/QQ/Email）需要结合各自实现判断完整度（配置字段/鉴权/消息回调是否齐全）。

#### 5.9.3 OpenAI 兼容接口支持现状

- 支持路由：
    - `/v1/chat/completions`（非 stream）
    - `/v1/models`
    - `/health`
- 限制：
    - 不支持 stream
    - 不支持任意 model（必须与启动时 modelName 相同）
    - tool_calls 的透传并不对外暴露（API 只输出最终文本 content）
    - usage 目前固定 0（未从 LLMResponse 反推）

---

### 5.10 基础设施模块（你点名的清单逐条对齐）

- Config / ConfigLoader：见第 6 节
- RuntimePaths：~/.ricbot 路径治理
- FsPathUtils / DisplayPathUtils：路径规范化与展示缩写
- PromptTemplates：模板加载与变量替换
- ToolHintFormatter：把 tool_calls 转成人类可读提示（便于 IM 渠道“思考提示”）
- NetworkSecurity：SSRF 防护（Web/Exec/命令 URL 扫描）
- RuntimeUtils：空回复兜底等
- RestartSupport：重启通知 env overlay（与 bin/ricbot 的 restart loop 联动）
- CronExpressionUtils / CronService / CronTypes：cron 存储/next run/执行/历史
- HeartbeatService：读取 HEARTBEAT.md、LLM 决策、执行与通知

---

## 6. 配置系统详解

### 6.1 配置文件在哪里

ConfigLoader 的查找优先级（见 [ConfigLoader.getConfigPath](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/config/ConfigLoader.java#L52-L74)）：

1. 代码中手动 set 的 currentConfigPath（CLI --config 会触发）
2. 环境变量：`RICBOT_CONFIG`
3. 系统属性：`-Dricbot.config=...`
4. 默认：`~/.ricbot/config.json`

仓库里还提供了示例/便捷配置：
- `config/ricbot.config.json`（示例）
- 根目录 `ricbot.config.json`（看起来是拷贝/备用）

### 6.2 ricbot.config.json 大致负责什么（以仓库示例说明）

示例文件：[config/ricbot.config.json](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/config/ricbot.config.json)

它表达了三大块：
- `agents.defaults`：workspace、model、max_tool_iterations、max_tool_result_chars、unified_session、timezone
- `providers`：openai 的 api_key/api_base（此处用 `${RICBOT_API_KEY}` 占位符）
- `tools`：
    - restrictToWorkspace
    - web.enable（示例为 false）
    - exec.enable/timeout/sandbox/path_append/allowed_env_keys

注意：Config.java 默认 model 是 gpt-4o；示例配置把 model 设为 `qwen-plus`，且 providers.openai.api_base 指向 DashScope OpenAI 兼容地址。这正体现了 ricbot 的策略：**“模型名与 provider 推断 + openai_compat 协议 + 统一工具循环”**。

### 6.3 Config.java 中“大模块”的含义

[Config.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/config/Config.java) 顶层字段：

- agents：AgentDefaults（模型/温度/maxTokens/迭代次数/上下文窗口/禁用技能/会话 TTL 等）
- providers：各 provider 的 ProviderConfig（api_key/api_base/extra_headers）
- tools：工具开关与策略（restrictToWorkspace、ssrf_whitelist、web/exec/mcp_servers）
- channels：渠道配置与出站策略（send_progress/send_tool_hints/transcription_provider/各渠道 section）
- gateway：服务端口、heartbeat 配置等
- api：API 相关扩展配置（当前 API server 的核心参数主要由 serve() 直接传入）

### 6.4 provider 如何根据 model 推断（非常关键）

逻辑在 `Config.getProviderName(model)`：

1. 若 model 带前缀 `provider/model`，优先用前缀识别 provider。
    - 并支持别名（claude→anthropic，gpt→openai，copilot→github_copilot）
2. 否则按 ProviderRegistry 的 keyword 匹配（如模型名包含 qwen/claude/gpt 等）。
3. 否则按 providers 中配置的 apiBase 做 base keyword 猜测。
4. 最后 fallback openai。

风险点（真实工程会遇到）：
- 聚合网关（openrouter/aihubmix/自建代理）会让 apiBase/模型名推断变得不可靠。
- 最稳妥做法是显式指定 model 前缀：例如 `openai/gpt-4o` 或 `anthropic/claude-3-5-sonnet`。

### 6.5 tools.web / tools.exec / tools.mcp_servers 如何影响行为

- tools.restrictToWorkspace：
    - 影响 AgentLoop 注册工具时的 allowedDir
    - ExecTool 进一步限制 working_dir 越界与绝对路径访问
- tools.web.enable：
    - 决定 AgentLoop 是否注册 `web_fetch` / `web_search`
- tools.exec.enable：
    - 决定 AgentLoop 是否注册 `exec` 与 `spawn`（spawn 依赖子代理）
- tools.exec.sandbox：
    - 当前效果：AgentLoop 会把 sandbox 标记传给 ExecTool；ExecTool 检测到 sandbox 非空会直接拒绝执行（不是隔离）
- tools.mcp_servers：
    - 若非空，AgentLoop 会 `mcpLoader.load()`，并把 MCP server 的能力注册为工具
    - server 配置字段可参考 MCPAdapters.parseMcpServers：type/url/command/args/env/enabled_tools/tool_timeout

### 6.6 哪些配置已真正落地，哪些是预留（诚实清单）

已落地/生效明确的：
- agents.defaults.workspace/model/max_tool_iterations/max_tool_result_chars/unified_session/timezone/session_ttl_minutes（主要在 Bootstrapper→AgentLoop）
- tools.restrictToWorkspace、tools.web.enable、tools.exec.enable/timeout/path_append/allowed_env_keys
- tools.ssrf_whitelist（ConfigLoader.applySsrfWhitelist → NetworkSecurity）
- gateway.port + gateway.heartbeat.enabled/interval_s（serve 模式启动 heartbeat 与 api server）

明显预留/未完全闭环的：
- agents.defaults.dream.*：存在配置结构，但 AgentLoop 固定每 15 分钟跑 Dream，不读取 enabled/cron。
- tools.exec.sandbox：字段存在，但语义是“禁用执行”而非“隔离执行”。
- tools.mcp_servers 的 typed 版本构造器存在，但主链仍主要用 Map<String,Object>（弱类型）。
- API 的更多路由（比如 /v1/responses、/v1/audio 等）未实现。

---

## 7. 启动与使用方式

### 7.1 本地开发环境要求

- Java：17（pom.xml 的 maven.compiler.release=17，见 [pom.xml](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/pom.xml#L11-L15)）
- Maven：
    - 推荐使用仓库自带 wrapper：`./mvnw`
- 外部依赖（运行时）：
    - LLM provider 的 API Key（至少一个）
    - 若启用 WebSearch 的特定 provider（brave/tavily/kagi/jina/searxng），需对应 key/baseUrl
    - 若启用 Channel，需要各渠道 token/secret（取决于渠道实现）
    - 若启用 MCP stdio，需要本机可执行 MCP server command

常见环境变量：
- `RICBOT_CONFIG`：配置文件路径
- `RICBOT_API_KEY`：示例配置里的 `${RICBOT_API_KEY}`（对应 openai provider）
- `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `DASHSCOPE_API_KEY` 等（取决于你配置用哪个 provider）
- `RICBOT_MAX_CONCURRENT_REQUESTS`：AgentLoop 并发上限（默认 3）
- `RICBOT_SKILLS_MAX_SELECTED` / `RICBOT_SKILLS_MAX_CHARS`：技能路由限制

### 7.2 启动方式

#### 7.2.1 命令行启动（推荐）

使用脚本（macOS/Linux）：
- [bin/ricbot](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/bin/ricbot)

它会：
1. 尝试从 `target/Ricbot-*.jar` 找最新 jar
2. 若没有则 `./mvnw -q package` 构建
3. `java -jar` 运行
4. 若进程退出码为 100，会自动重启（与 RestartSupport 的设计意图联动）

常用命令：
```bash
# 查看帮助
./bin/ricbot

# 查看版本
./bin/ricbot --version

# 查看状态（读取默认配置路径）
./bin/ricbot status

# 使用示例配置运行一次对话
export RICBOT_API_KEY="YOUR_KEY"
./bin/ricbot agent --config config/ricbot.config.json --workspace . --message "你是谁？"
```

#### 7.2.2 CLI agent 模式

交互式：
```bash
./bin/ricbot agent --config config/ricbot.config.json --workspace . --session cli:direct
```

单次消息：
```bash
./bin/ricbot agent --config config/ricbot.config.json --workspace . --message "帮我列出当前目录结构" 
```

说明：
- `--session` 影响会话落盘 key（CLI 默认 `cli:direct`）。
- CLI 会请求流式输出（metadata `_wants_stream=true`），因此你可能看到工具提示/增量输出（取决于 provider 是否真正支持 stream）。

#### 7.2.3 serve 模式（多渠道服务 + API + Heartbeat）

```bash
export RICBOT_API_KEY="YOUR_KEY"
./bin/ricbot serve --config config/ricbot.config.json --workspace .
```

serve 会启动：
- AgentLoop（消息处理主循环）
- ChannelManager（启动所有 enabled channel，并派发 outbound）
- HeartbeatService（按 interval 检查 HEARTBEAT.md）
- RicbotApiServer（OpenAI 兼容 API，默认端口来自 config.gateway.port）

#### 7.2.4 打包运行（fat jar）

pom.xml 使用 maven-shade-plugin 生成可执行 jar，mainClass 指向 RicbotApplication：
- [pom.xml](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/pom.xml#L122-L149)

构建：
```bash
./mvnw -q package
ls -lh target/Ricbot-*.jar
java -jar target/Ricbot-*.jar agent --config config/ricbot.config.json --workspace . --message "hello"
```

#### 7.2.5 在 IDE 调试运行

在 IntelliJ IDEA：
- Main class：`ricbot.app.bootstrap.RicbotApplication`
- Program arguments 示例：
    - `agent --config config/ricbot.config.json --workspace . --message "debug test"`
    - `serve --config config/ricbot.config.json --workspace .`
- Environment：
    - `RICBOT_API_KEY=...`（或你配置引用的其他 key）

### 7.3 常见运行场景

#### 7.3.1 只跑 CLI（最低依赖）

- 禁用 web 工具：tools.web.enable=false（示例已是 false）
- 可禁用 exec：tools.exec.enable=false
- 只需要 provider 能 chat 即可

#### 7.3.2 开 HTTP API 服务（OpenAI 兼容）

- 用 serve 模式启动（它会启动 RicbotApiServer）
- 客户端用 OpenAI SDK 之类访问：
    - base_url：`http://localhost:<gateway.port>/v1`
    - 注意：stream 不支持

#### 7.3.3 启用 web/exec 工具

- tools.web.enable=true
- tools.exec.enable=true（示例为 true）
- 安全策略：
    - restrictToWorkspace=true（建议默认开启）
    - 配置 ssrf_whitelist（仅当你需要访问内网资源且可控时）

#### 7.3.4 启用 MCP

- 在 config.tools.mcp_servers 配置 server
- 示例（概念性，字段以 MCPAdapters.parseMcpServers 为准）：
```json
{
  "tools": {
    "mcp_servers": {
      "my_server": {
        "type": "stdio",
        "command": "node",
        "args": ["path/to/mcp-server.js"],
        "env": {"FOO": "bar"},
        "enabled_tools": ["toolA", "toolB"],
        "tool_timeout": 30
      }
    }
  }
}
```

#### 7.3.5 启用 channel

- 在 config.channels.<channelName>.enabled=true，并配置 allowFrom/token 等（取决于 channel 实现）
- serve 启动后 ChannelManager 会 discoverAll 并启用对应渠道。

#### 7.3.6 启用 cron/heartbeat

- cron：AgentLoop startBackgroundIfNeeded() 会启动 CronService（只要 AgentLoop start，就会 start cronService）
- heartbeat：serve 模式会启动 HeartbeatService（取决于 gateway.heartbeat.enabled）

### 7.4 示例命令与使用示例（更贴近工程）

```bash
# 1) 用示例配置跑一次
export RICBOT_API_KEY="..."
./bin/ricbot agent --config config/ricbot.config.json --workspace . --message "读取 pom.xml 并总结依赖"

# 2) 查看工具列表与安全限制（可用于排查“为什么 exec 不可用”）
./bin/ricbot tools --config config/ricbot.config.json --workspace .

# 3) 启动服务（API + channels + heartbeat）
./bin/ricbot serve --config config/ricbot.config.json --workspace .

# 4) 测试 API（非 stream）
curl -s http://localhost:8080/health
curl -s http://localhost:8080/v1/models

curl -s http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen-plus",
    "session_id": "demo",
    "messages": [{"role":"user","content":"hello"}],
    "stream": false
  }'
```

---

## 8. OpenAI 兼容 API 使用说明

本节以 ricbot 的实际实现为准（不是泛化的 OpenAI 标准）。

实现参考：[RicbotApiServer.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/api/RicbotApiServer.java)

### 8.1 /health

- 方法：GET
- 响应：
```json
{"status":"ok"}
```

用途：探活（容器探针 / 负载均衡健康检查）。

### 8.2 /v1/models

- 方法：GET
- 响应示例：
```json
{
  "object": "list",
  "data": [
    {"id":"qwen-plus","object":"model","created":0,"owned_by":"ricbot"}
  ]
}
```

注意：
- 只返回“启动时传入的 modelName”（serve 里传的是 defaults.model）。

### 8.3 /v1/chat/completions

- 方法：POST
- 核心限制：
    - `stream=true` 会直接 400（当前版本不支持实时流式输出）
    - `model` 必须等于服务启动时的 modelName，否则 400
- ricbot 扩展字段：
    - `session_id`：用于会话隔离；会映射为内部 `sessionKey="api:"+session_id`

#### 8.3.1 请求体结构（ricbot 实际解析）

ricbot 主要关注字段：
- messages：必须是非空数组
- model：必须匹配 modelName（若提供）
- stream：若 true 直接报错
- session_id：可选

messages 支持 role：
- system / user / assistant / tool

tool role 支持字段：
- tool_call_id / name / content

assistant role 支持：
- tool_calls（list），但 API 侧只是把它当历史同步的一部分，并不会对外“执行工具调用”历史；真正工具循环由 AgentLoop/AgentRunner 决定。

#### 8.3.2 session_id 的作用（非常具体）

- 如果你传了 `session_id="demo"`，内部会话键是 `api:demo`。
- SessionManager 会把该 session 的 messages 落盘在 workspace/sessions 下对应文件。
- API server 对同一 `api:demo` 会加 sessionLock，保证串行处理。

典型用法：
- 前端/业务系统把“一个用户/一个会话”映射为 session_id，即可获得上下文连续对话。

#### 8.3.3 内部如何转发到 AgentLoop

- parseMessages：
    - normalized 历史 = messages（可能剥离最后 user）
    - currentUserContent = 最后 user content（若最后不是 user，则 currentUserContent 可能为空）
    - shouldSyncHistory：只要 messages>1 或包含非 user 角色则为 true
- 若 shouldSyncHistory：
    - 直接把 history 写入 session.setMessages(history) 并 save（这是“外部强同步历史”的行为）
- 调用：
    - `agentLoop.processDirect(userContent, sessionKey, "api", API_CHAT_ID)`
- responseText：
    - 若返回 OutboundMessage，则取 msg.content
    - 为空则自动重试一次；仍为空则用兜底常量

#### 8.3.4 stream 的现状（必须诚实）

- API server 层：**不支持**（明确拒绝）
- 但 Provider 层（OpenAICompatProvider）是支持真正 SSE 消费的
- CLI 与 WebSocketChannel 有“伪流式/消息流式”能力（通过 OutboundMessage 的 delta 标记）
- 因此：
    - 想要流式体验：优先走 CLI 或 WebSocketChannel
    - 若要 HTTP stream：需要对 RicbotApiServer 增加 SSE 输出并与 AgentHook/MessageBus 协作（属于未来工作）

#### 8.3.5 适用场景与局限

适用：
- 业务系统希望用 OpenAI SDK 兼容协议调用 ricbot 的 agent 能力
- 需要 session_id 管理上下文

局限：
- 不能 stream
- 模型名固定（不是动态多模型路由）
- usage 不准确（固定 0）
- tool_calls 不对外暴露（只有最终文本）

---

## 9. Session / Memory / Cron / Heartbeat 的运行机制

### 9.1 Session：创建、读取、保存

创建/读取：
- AgentLoop.processMessage → `sessionManager.getOrCreate(sessionKey)`
- SessionManager.load 会从 sessionsDir 下读取对应 jsonl
- 若不存在则 new Session(key)

保存：
- SessionManager.save：
    - 写 tmp 文件（.tmp）
    - 原子 move（ATOMIC_MOVE 优先）
    - 第一行写 metadata，然后逐行写 message JSON

中断恢复机制（与 Session metadata 强相关）：
- `pending_user_turn`：用户消息已写入 session，但 assistant 回复未完成
    - 下次恢复时会追加一条“错误：任务在生成回复前被中断。”
- `runtime_checkpoint`：工具调用中间态（assistant_message、completed_tool_results、pending_tool_calls）
    - 恢复时会把 pending tool_calls 变成 tool role 的错误消息（比如“任务在该工具执行完成前被手动停止/超时/shutdown”）

### 9.2 Memory：归档、Dream、版本化

归档（Consolidator）：
- 当会话 token 估算超预算：
    - 切掉一段旧消息 → archive
    - archive 通过 LLM 生成摘要写入 `memory/history.jsonl`
    - 切掉的消息从 session.messages 删除，从而缩短上下文

Dream：
- 定期读取 `history.jsonl` 中 dream_cursor 之后的条目
- 让 LLM 输出 MEMORY/USER/SOUL 的完整新内容
- 写回并 Git 提交

版本化（GitStore）：
- Dream 第一次执行会 init git（在 workspace/.git）
    - 注意：这个 .git 是“记忆仓库”，但它是在 workspace 根目录初始化的，需要确认你的 workspace 是否就是业务代码目录；如果是，会与业务代码 git 冲突（风险点！）。
    - GitStore 的 .gitignore 会忽略全部文件，只保留被追踪的记忆文件和 .gitignore 本身，这是一种“把 workspace 变成记忆仓库”的做法。
- /dream-log：显示提交列表
- /dream-restore <sha>：checkout 受管文件并生成 revert commit

维护者必须关注的风险：
- 如果 workspace 指向一个本来就有 git 仓库的项目目录，Dream 的 GitStore.init 会失败或造成混乱。建议将 workspace 设置为专用目录，而不是源码仓库根目录。

### 9.3 Cron：存储、计算 next run、执行 job

CronService 存储：
- 默认路径：`workspace/.ricbot/cron/store.json`（AgentLoop 构造）
- action log：同目录 `action.jsonl`
- store.json 结构包含 version/jobs（CronTypes 定义）

计算 next run：
- at：at_ms > now 才有效
- every：now + every_ms
- cron：CronExpressionUtils.nextExecutionMillis(expr, tz, now)

执行：
- CronService 到期触发 onJob(job)
- AgentLoop.handleCronJob(job)：
    - 构造 InboundMessage（channel/chatId/message）
    - metadata 加 `_cron_job_id/_cron_job_name/_deliver`
    - executor.submit(() -> dispatch(msg))

现状提示：
- `_deliver` 在 ChannelManager 侧并没有直接使用（是否投递可能要靠上层/渠道逻辑扩展）。目前更多是“为未来投递策略预留的 metadata”。

### 9.4 Heartbeat：读取 HEARTBEAT.md、决策执行、通知

HeartbeatService（serve 模式启动）：
1. 每 interval 读取 `workspace/HEARTBEAT.md`
2. decide：
    - 用 LLM 调用 heartbeat 工具（HEARTBEAT_TOOL schema）
    - tool arguments：action=skip/run，tasks=任务摘要
3. 若 run：
    - onExecute(tasks)：在 serve 模式中，CliCommands 传入的 onExecute 会调用 `agentLoop.processDirect(tasks, "heartbeat:default", "system", "heartbeat")`
4. evaluate：
    - EvaluateResponseHelper.evaluateResponse(response, tasks, provider, model)
    - 决定是否需要通知
5. onNotify(response)：
    - serve 模式中会发布 outbound 到 bus，让 ChannelManager/CLI 处理

现状提示：
- Heartbeat 的“任务 DSL”来自模板 `templates/HEARTBEAT.md`（resources/templates 下有默认模板），实际规则强依赖你如何维护 HEARTBEAT.md 的内容。

---

## 10. 安全与稳定性设计

### 10.1 SSRF 防护

核心实现：[NetworkSecurity.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/security/NetworkSecurity.java)

应用点：
- WebFetchTool：先 validateUrlSafe（内部会调用 NetworkSecurity.validateUrlTarget/validateResolvedUrl）
- WebSearchTool：searxng baseUrl 会做 SSRF 校验
- ExecTool：扫描命令字符串中的 URL，只要命中内网地址就拒绝执行（containsInternalUrl）

策略：
- 默认拦截私网/回环/链路本地/CGNAT 等网段
- 支持 `tools.ssrf_whitelist` 配置 CIDR 白名单（ConfigLoader.applySsrfWhitelist）

### 10.2 路径安全校验

文件工具：
- FileToolSupport.resolvePath：相对路径基于 workspace 解析
- FileToolSupport.ensureAllowed：限制必须在 allowedDir 或 extraAllowedDirs 下

执行工具：
- ExecTool 在 restrictToWorkspace 时：
    - 禁止 `../` 等路径穿越
    - 提取命令中的绝对路径，禁止超出 working_dir 与 media_dir

### 10.3 Retry / CircuitBreaker

- LLMProvider：内置重试（针对 408/409/429 等；并区分不可重试的 quota/billing）
- WebFetchTool：结合 CircuitBreaker（连续失败熔断）与指数退避 RetryUtils

### 10.4 LLM 重试策略

- AgentRunSpec.providerRetryMode：
    - "none/off/disabled"：直接 provider.chat
    - 否则：provider.chatWithRetry
- Provider 自身也可能实现更细粒度的 retry-after

### 10.5 Session 锁与一致性

- AgentLoop：sessionLocks（Object monitor），保证同会话串行执行，避免消息乱序与文件竞争。
- RicbotApiServer：sessionLocks（ReentrantLock），保证同 session_id 的 HTTP 请求串行。

### 10.6 API 超时控制

- RicbotApiServer.runWithTimeout：每个请求用单线程 executor + Future.get(timeout) 实现超时控制
- 超时返回 504

### 10.7 MCP / Web / Exec 的风险点

- MCP stdio：本质是启动子进程并执行外部 server，风险包括：
    - command 注入/路径不可信
    - server 行为不受控（读写文件/网络）
- WebFetch/WebSearch：虽然有 SSRF，但仍存在：
    - 对外网内容的 prompt 注入风险（WebFetchTool 会加 UNTRUSTED_BANNER，但最终是否被模型遵循取决于系统 prompt）
- ExecTool：
    - denyPatterns/allowPatterns/SSRF/path guard 能降低风险，但不能等同于真正 sandbox
    - 当前 sandbox 配置不实现隔离，建议生产环境默认关闭 exec 或开启严格 allowPatterns

### 10.8 当前不足（客观列举）

- sandbox 没有真实隔离（是“拒绝执行”）
- API server 没有 stream，且 usage 不真实
- Dream 的 GitStore 在 workspace 根目录 init git 可能与业务代码 git 冲突（高风险）
- MCP wrapper 每次调用创建线程池（成本与资源泄露风险需要评估）
- 统一的审计日志/权限体系还不够体系化（目前主要靠 System.out/err + slf4j）

---

## 11. 当前项目的已完成能力与未完成能力

### 11.1 已完成/较完整

- AgentLoop 主链路：MessageBus → Session/Memory/Skills/Tools → AgentRunner → Outbound
- Tool 调用循环：并发执行、工具结果封装、hook、checkpoint
- 会话持久化：jsonl 格式、原子写入、迁移兼容
- Memory 基建：MEMORY/USER/SOUL + history.jsonl + cursor + Dream 更新与版本化（基础可用）
- Cron：CronService + CronTool + AgentLoop cron job dispatch（可用）
- WebFetch/WebSearch：SSRF + 熔断重试 + 抽取策略（可用）
- ExecTool：多层 guard（可用但需谨慎）
- WebSocketChannel：具备较完善的 streaming outbound 能力

### 11.2 基础版/功能可用但有缺口

- OpenAI 兼容 API：能用，但不支持 stream、usage 固定、模型固定
- Skill 体系：规则路由可用，但需要持续维护关键词/权重/预算策略
- Subagent：可 spawn 并回灌，但缺少更精细的资源配额/结果结构化与可观测性

### 11.3 占位/未完全落地

- provider login（OAuth）：CLI 占位
- /restart：AgentLoop 命令禁用；bin/ricbot 支持 exit code=100 重启，但缺少触发链路闭环
- MCP streamableHttp：未实现
- GitStore.diffCommits：占位
- DreamConfig（enabled/cron）：配置存在但运行调度未接入
- 部分命名残留 nanobot（注释/提交 message 等）：需要统一

### 11.4 重复/边界不清的点（设计味道）

- workspace 概念同时承担“工具允许范围”与“记忆仓库根目录”的角色，且 Dream 可能 init git；在工程实践中这两个职责应拆分（避免污染业务仓库）。
- API 层与 AgentLoop 都有 session 锁，双层锁策略虽然安全，但可能造成复杂性；未来需要统一“会话并发模型”。
- Tools 的配置与注册分散在 AgentLoop.registerDefaultTools 与 CLI tools 命令里，有重复初始化逻辑。

---

## 12. 后续优化建议

### 12.1 架构与目录收敛

- 统一命名：nanobot → ricbot（类名、注释、GitStore init message、目录/包名 tools vs tool）
- 将 `ricbot/tool` 与文档中的 `tools/` 统一（建议以 `tool` 为准，或整体改为 `tools`，但要一次性收敛）
- 将 Config 巨型类拆分：
    - agents/providers/tools/channels/gateway/api/memory 分文件
    - 引入 schema 校验（JSON schema / Jakarta validation）

### 12.2 MCP 收敛与稳定化

- 实现 streamableHttp（或明确不支持并从配置层禁用）
- wrapper 调用改为复用线程池/连接级 executor，避免频繁创建
- 对 MCP tool/resource/prompt 的返回结构做更严谨的 block 处理（保留富内容，而不是 String.valueOf）

### 12.3 LLM 分层与观测

- 抽象统一的 “LLM call tracing”：
    - 请求 id、sessionKey、iteration、model、latency、token usage
- API 层把 usage 从 AgentRunResult/LLMResponse 回填到 OpenAI 响应中
- 统一 stream 的实现路径：
    - API server 支持 SSE，并与 AgentHook 输出对齐

### 12.4 配置治理

- 明确 model/provider 的契约（建议 model 明确写成 `provider/model`）
- 为 tools.exec 增加 allowPatterns 的配置入口（当前 ExecTool 支持，但 Config 未暴露 deny/allow）
- DreamConfig 真正接入调度（enabled/cron/tz），并提供手动触发入口

### 12.5 错误处理与日志

- 统一日志：避免 System.out/err 与 slf4j 混用（尤其是 API server 与 channel）
- 工具执行错误结构化（现在是字符串 Error: ...，可改为统一 JSON 结构，方便 LLM/前端解析）

### 12.6 安全增强

- 对 WebFetch 结果增加更强的“不可信内容”隔离（例如强制放入单独 context block，并在 system prompt 中强制策略）
- ExecTool 真正 sandbox：
    - macOS 可考虑 `sandbox-exec`（但需要谨慎）或容器化执行
    - 更推荐“默认关闭 exec + 严格 allowPatterns + 专用执行用户/目录”
- workspace 与 memory 仓库分离，避免 Dream git 污染业务目录

### 12.7 测试覆盖与工程化

- 增加端到端测试：
    - CLI agent 单次消息
    - API /v1/chat/completions
    - 工具调用循环（含并发 tools）
- 引入格式化/静态检查（spotless/checkstyle/spotbugs）形成稳定 CI

---

## 13. 给新开发者的阅读顺序建议

### 13.1 第一阶段：先跑起来（理解系统形态）

1. 看入口与命令：
    - [RicbotApplication.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/bootstrap/RicbotApplication.java)
    - [CliCommands.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/app/cli/CliCommands.java)
2. 跑一个最小命令（建议只启用 exec 或只读工具，先确认链路通）
3. 理解配置加载：
    - [ConfigLoader.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/config/ConfigLoader.java)
    - [Config.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/config/Config.java)

你需要搞懂：
- config 从哪里来
- model/provider 怎么选
- 工具开关如何影响运行行为

### 13.2 第二阶段：掌握主链路（能回答“消息怎么走”）

按顺序读：
1. [MessageBus.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/message/MessageBus.java)
2. [AgentLoop.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentLoop.java)
3. [ContextBuilder.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/ContextBuilder.java)
4. [AgentRunner.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/agent/AgentRunner.java)
5. [ToolRegistry.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/api/ToolRegistry.java)

你需要搞懂：
- sessionKey/锁/并发
- 工具循环如何执行与封装 tool role message
- hook 如何实现“流式输出/工具提示”

### 13.3 第三阶段：理解持久化与长期记忆（能维护用户体验）

1. [SessionManager.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/session/SessionManager.java)
2. [MemoryStore.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/MemoryStore.java)
3. [Consolidator.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/Consolidator.java)
4. [Dream.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/domain/memory/Dream.java)
5. [GitStore.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/git/GitStore.java)

你需要搞懂：
- session jsonl 格式与 checkpoint/pending 恢复
- history.jsonl 游标机制
- Dream 对 workspace git 的影响与风险

### 13.4 第四阶段：对外接入（能扩展渠道、API、MCP）

- API：
    - [RicbotApiServer.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/api/RicbotApiServer.java)
- Channel：
    - [ChannelManager.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/ChannelManager.java)
    - [BaseChannel.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/BaseChannel.java)
    - [WebSocketChannel.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/channel/WebSocketChannel.java)
- MCP：
    - [MCPLoader.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPLoader.java)
    - [MCPAdapters.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPAdapters.java)
    - [MCPTransportFactory.java](file:///Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/integration/mcp/MCPTransportFactory.java)

你需要搞懂：
- outbound 的流式 delta 如何被 ChannelManager 合并与发送
- API 的 session_id 如何映射到内部会话
- MCP 工具如何注册成 `mcp_<server>_<tool>` 并参与工具循环

---

## 附录 A：测试与验证

项目测试位于 `src/test/java`，覆盖：
- agent：AgentLoopTest/AgentRunnerTest/AgentLoopToolCallTest
- session：SessionManagerTest
- skill：SkillRouterTest
- cron：CronServiceTest
- heartbeat：HeartbeatServiceTest
- mcp：MCPAdaptersTest/MCPIntegrationTest
- api/channel：RicbotApiServerTest/WebSocketChannelTest

运行：
```bash
./mvnw test
```

---

## 附录 B：一份“最小可用”配置模板（建议起步）

> 下面示例强调：restrictToWorkspace=true、web 默认关、exec 可按需开。  
> 注意把 api_key 的环境变量替换正确。

```json
{
  "agents": {
    "defaults": {
      "workspace": ".",
      "model": "openai/gpt-4o",
      "max_tool_iterations": 6,
      "max_tool_result_chars": 10000,
      "unified_session": false,
      "timezone": "UTC"
    }
  },
  "providers": {
    "openai": {
      "api_key": "${OPENAI_API_KEY}",
      "api_base": "https://api.openai.com/v1",
      "extra_headers": {}
    }
  },
  "tools": {
    "restrictToWorkspace": true,
    "ssrf_whitelist": [],
    "web": { "enable": false },
    "exec": {
      "enable": false,
      "timeout": 60,
      "sandbox": false,
      "path_append": "",
      "allowed_env_keys": []
    },
    "mcp_servers": {}
  },
  "gateway": {
    "port": 8080,
    "heartbeat": {
      "enabled": false,
      "interval_s": 30,
      "keep_recent_messages": 50
    }
  }
}
```

---