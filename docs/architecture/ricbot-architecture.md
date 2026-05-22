# Ricbot Architecture

Ricbot 是一个 Java 17 Agent Runtime，把模型调用、工具执行、团队协作、工作区变更、经验沉淀、评测门禁和本地 Console 观测串成一个可演示、可回放、可治理的工程闭环。

## Layers

```text
CLI / API / Channel
  -> AgentLoop / AgentRunner
  -> Provider / Capability
  -> ToolRegistry / MCP
  -> Context / Memory / Experience
  -> Team / Workspace / ChangeSet
  -> Eval / Release Gate
  -> Console / Gateway
```

### CLI / API / Channel

入口层提供三类使用方式：

- CLI：单次 agent、交互命令、team、eval、config doctor。
- API：OpenAI-compatible `/v1/chat/completions`、sessions、MCP dashboard、health。
- Channel/Gateway：Feishu、DingTalk、WeCom webhook 文本入站和多渠道消息出口。

这一层负责把外部输入标准化成 session、message 或 command，不直接承载模型推理策略。

### AgentLoop / AgentRunner

`AgentLoop` 是会话级编排层，负责 session、context、memory、tools、trace、team 命令路由和运行时依赖。`AgentRunner` 是单次模型-工具循环执行器，负责：

- 构建模型请求；
- 暴露工具 schema；
- 执行 tool calls；
- 处理 hook、checkpoint、progress；
- 根据 provider capability 做 tool/streaming/vision 降级。

### Provider / Capability

Provider 层通过 OpenAI-compatible、Anthropic、Azure OpenAI 等适配器发起模型调用。Capability 层不做在线探测，而是通过静态/启发式规则和用户 `model_capabilities` override 生成最终能力结果。

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

### Context / Memory / Experience

Context 层负责把 session、workspace、skills、memory 和 policy 组合成模型上下文。Memory 与 Experience 层负责：

- 抽取候选经验；
- 人工验证或拒绝；
- 将 verified experience promote 成 generated skill；
- 避免未经验证的经验污染上下文。

### Team / Workspace / ChangeSet

Team 层把复杂任务拆成 planner / implementer / verifier 等角色执行。Workspace 层支持本地 workspace 和受管 git worktree。ChangeSet 层把 worktree diff 收口为可审阅变更，避免 agent 直接把未审阅改动并入主工作区。

### Eval / Release Gate

Eval 层提供 deterministic smoke provider、golden scenarios、baseline 管理和 compare。Release Gate 用 `scripts/release-check.sh` 串联：

- 全量测试；
- package；
- config doctor；
- fixed smoke eval；
- baseline compare；
- release report。

### Console / Gateway

Console 是本地只读优先的运行态观测面，展示 Config Doctor、Trace、Team Reports、Workspaces、Experience、Eval Runs、Release Check、Tools/MCP 和 Console Actions。Gateway 承载本地 API、Console 和企业 IM webhook 入站。

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
  -> TraceStore / SessionPersistence / Experience extraction
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
- Worktree-backed team task 隔离修改，ChangeSet 作为人工审阅边界。
- Release Check 使用 deterministic smoke eval，不要求真实模型或外网。
- Webhook 入站只支持文本和已实现校验，不解密 Feishu/WeCom 加密事件。
- MCP diagnostics 只读，不启动、停止、reload、reconnect 或调用 MCP tool。
- config doctor、Console、trace 和 MCP diagnostics 对敏感字段脱敏。

## Extension Points

- Provider：新增 `ProviderSpec` 和对应 `LLMProvider` 适配器。
- Capability：在静态 resolver 中补启发式，或通过 `model_capabilities` 做本地 override。
- Tool：实现 `Tool` 并注册到 `ToolRegistry`。
- MCP：在 `tools.mcpServers` 中接入 stdio / sse / streamableHttp server。
- Skills：把 verified experience promote 为 generated skill，或手写 skill。
- Channel：实现 `BaseChannel`，接入 `ChannelManager` 和 webhook controller。
- Eval：新增 JSONL scenario，扩展 baseline 和 compare。
- Console：新增只读 service + endpoint + card；写操作必须走 auth、CSRF-lite、rate limit 和 audit。
