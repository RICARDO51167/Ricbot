# Ricbot Architecture

Ricbot 是一个面向长任务与多智能体协作的 Java 持久化 Agent Runtime，围绕持久执行、精确恢复、副作用安全、Worker 协作、工作区隔离和评测验证组织能力。

## Layers

```text
CLI / API / Channel
  -> AgentLoop / AgentRunner
  -> Provider / Capability
  -> ToolRegistry / MCP
  -> Context / Memory / Skills
  -> Team / Workspace / ChangeSet
  -> Eval / Release Gate
  -> Console / Gateway
```

### CLI / API / Channel

入口层提供三类使用方式：

- CLI：单次 agent、交互命令、team、eval、config doctor。
- API：OpenAI-compatible `/v1/chat/completions`、sessions、MCP dashboard、health。
- Channel：保留通用 WebSocket 输入适配器；具体业务渠道应作为 Core Runtime 之外的插件。

这一层负责把外部输入标准化成 session、message 或 command，不直接承载模型推理策略。

### AgentLoop / AgentRunner

`AgentLoop` 是会话级编排层，负责 session、context、memory、tools、trace、team 命令路由和运行时依赖。`AgentRunner` 是单次模型-工具循环执行器，负责：

- 构建模型请求；
- 暴露工具 schema；
- 执行 tool calls；
- 处理 hook、checkpoint、progress；
- 根据 provider capability 做 tool/streaming/vision 降级。

执行控制不再只由隐式 ReAct 循环表达。`AgentGraphRuntime` 支持注册节点、条件边、暂停和 cursor 恢复；`AgentNodeScheduler` 用默认 graph 维护 `MODEL -> TOOLS -> MODEL/TERMINAL` 兼容行为。节点迁移、模型边界和工具边界都写入 durable journal。`RunCheckpoint` 保存节点、消息和 journal sequence，可按历史版本原地恢复或 fork 到独立 session/run。

### Durable Runtime

核心运行态包含三条相互校验的持久化链：

- `RunJournalStore`：类型化、单调序号的事件源和可重建 `RunState`；
- `RunCheckpointStore`：latest pointer 加不可变历史版本，用于恢复模型上下文；
- `SideEffectStore`：写工具的幂等键、结果和补偿状态。

崩溃恢复默认 fail closed。已完成结果可以复用；未知的只读工具在名称、策略和参数摘要完全一致时可自动重试；未知副作用必须显式授权。断线客户端通过 `RunEventReplayService` 使用 exclusive cursor 补齐事件。

### Provider / Capability

Provider 层通过 OpenAI-compatible 与 Anthropic 两类适配器发起模型调用；Azure OpenAI 使用其 v1-compatible 端点、`api_base` 与 `extra_headers` 接入统一兼容适配器。Capability 层不做在线探测，而是通过静态/启发式规则和用户 `model_capabilities` override 生成最终能力结果。

运行时只消费最终 `ProviderCapability`：

- `supportsToolCalling=false`：不向模型暴露 tools；
- `supportsStreaming=false`：降级非流式；
- `supportsVision=false`：拒绝图片输入；
- `UNKNOWN`：保持既有行为。

### ToolRegistry / MCP

`ToolRegistry` 统一管理内置工具、生成技能工具和 MCP 工具。MCP 层负责：

- 解析 `tools.mcpServers`；
- 连接 stdio / sse / streamableHttp server；
- 把 MCP tools/resources/prompts 包装为 Ricbot tools；
- 提供只读 MCP diagnostics、schema snapshot 和启用解释。

### Context / Memory / Skills

Context 层负责把 session、workspace、skills、memory 和 policy 组合成模型上下文：

- 只召回已审批的结构化长期记忆；
- 由人工维护 Skill，不从运行结果自动晋升；
- 避免模型推断和低置信度内容污染上下文。

RAG 使用词法与向量信号混合排序，embedding provider 可替换，离线默认使用 deterministic feature hashing，也可显式接入 OpenAI-compatible embeddings。向量按 model 和 chunk fingerprint 持久化，tenant-specific index 使用散列目录隔离。`TenantMemoryService` 在共享 CAS store 上隔离租户，并管理 working、episodic、semantic、perceptual 分层和晋升。

### Team / Workspace / ChangeSet

Team 层把复杂任务拆成 planner / implementer / verifier 等角色执行。Workspace 层支持本地 workspace 和受管 git worktree。ChangeSet 层把 worktree diff 收口为可审阅变更，避免 agent 直接把未审阅改动并入主工作区。

`PersistentTeamRuntime` 为 worker 提供独立生命周期、跨进程 mailbox sequence、cursor/ack、broadcast、join 和 handoff。`TeamEngine` 创建任务时即分配持久 worker，真实 worker 与 verifier 执行会同步 RUNNING/COMPLETED/FAILED，并通过 mailbox 把结果交回 leader。

### Eval / Release Gate

Eval 层提供 deterministic smoke provider、golden scenarios、baseline 管理和 compare。Release Gate 用 `scripts/release-check.sh` 串联：

- 全量测试；
- package；
- config doctor；
- fixed smoke eval；
- baseline compare；
- release report。

`EvalMatrixRunner` 可对 provider/model 矩阵重复运行，聚合通过率、长轨迹覆盖、P50/P95、token、工具调用、失败类型和估算成本。`evals/long_trajectory.jsonl` 提供 12-turn 基础长轨迹。

### Console / Gateway

Console 是本地只读优先的运行态观测面，展示 Config Doctor、Trace、Team Reports、Workspaces、Eval Runs、Release Check、Tools/MCP 和 Console Actions。Gateway 承载本地 API、Console 和通用 WebSocket 入站。

## Core Call Chain

典型 CLI agent 调用链：

```text
CliCommands
  -> Bootstrapper.loadConfig / createProvider / createAgentLoop
  -> AgentLoop.processDirect
  -> ContextBuilder + SessionManager
  -> AgentExecutionService
  -> AgentRunner.run
  -> LLMProvider.chat/chatStream
  -> ToolRegistry.execute
  -> TraceStore / SessionPersistence / Memory candidates
```

典型 team worktree 调用链：

```text
/team run --worktree --verify
  -> TeamEngine / TeamExecutionService
  -> WorkspaceLifecycleService creates managed worktree
  -> Worker executes through AgentLoop
  -> VerificationService checks result
  -> TeamTaskReport summarizes
  -> /workspace diff
  -> /change create
```

典型 Console 观测链：

```text
serve
  -> RicbotApiServer
  -> ConsoleController
  -> ConfigDoctorService / TraceViewerService / EvalRunsViewerService
  -> ToolRegistryViewerService / MCPDiagnosticService
  -> JSON dashboard responses
```

## Safety Boundaries

- API 非 loopback 监听时必须配置 `api.bearer_token`。
- Console POST 复用 bearer auth、Origin/Referer 检查和轻量 rate limit。
- Console 写操作有审计记录，且仅开放人工确认型动作。
- File/exec/web 工具受 workspace 限制、SSRF 防护、审批和风险策略约束。
- `ExecTool` 通过 `ExecutionBackend` 执行；Local 有超时和进程树终止，Docker 默认断网且只挂载 workspace，Remote 可接 E2B/OpenSandbox/Kubernetes client。
- 副作用工具先持久化幂等 reservation；不确定状态不会自动重复执行。
- Worktree-backed team task 隔离修改，ChangeSet 作为人工审阅边界。
- Release Check 使用 deterministic smoke eval，不要求真实模型或外网。
- MCP diagnostics 只读，不启动、停止、reload、reconnect 或调用 MCP tool。
- config doctor、Console、trace 和 MCP diagnostics 对敏感字段脱敏。

## Extension Points

- Provider：新增 `ProviderSpec` 和对应 `LLMProvider` 适配器。
- Capability：在静态 resolver 中补启发式，或通过 `model_capabilities` 做本地 override。
- Tool：实现 `Tool` 并注册到 `ToolRegistry`。
- MCP：在 `tools.mcpServers` 中接入 stdio / sse / streamableHttp server。
- Skills：通过受审阅文件人工维护和加载。
- Channel：实现 `BaseChannel` 插件并接入 `ChannelManager`，不得反向依赖 Core Runtime。
- Eval：新增 JSONL scenario，扩展 baseline 和 compare。
- Execution：实现 `ExecutionBackend` 或 `RemoteExecutionClient`，通过能力探测显式选择；降级必须由调用方开启。
- Storage：实现 `SharedStateStore` 接入 SQL、Redis 或对象存储，保留 CAS 版本语义。
- Telemetry：通过标准 OTLP endpoint 环境变量启用 SDK/batch exporter；未配置时保持 no-op，并把 journal event 映射为 span event。
- Console：新增只读 service + endpoint + card；写操作必须走 auth、CSRF-lite、rate limit 和 audit。
