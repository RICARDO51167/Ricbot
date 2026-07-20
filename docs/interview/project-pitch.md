# Ricbot Project Pitch

## 30 秒版本

Ricbot 是我用 Java 17 做的 Agent Runtime。它不是单纯聊天机器人，而是把 OpenAI-compatible 模型、工具调用、团队式任务执行、受管 worktree、ChangeSet 审阅、经验沉淀、评测门禁和本地 Console 串成一个工程闭环。核心价值是让 Agent 的执行过程可诊断、可回放、可治理，而不是只看一次模型回答。

## 1 分钟版本

Ricbot 是一个面向 Coding Agent 和 AI Infra 的 Java Agent Runtime。它支持 CLI、OpenAI-compatible API、多渠道消息和本地 Console。内部有 AgentLoop/AgentRunner 负责模型-工具循环，ToolRegistry 管理内置工具和 MCP 工具，Team/Workspace 模块把复杂任务放到受管 git worktree 里执行，再通过 ChangeSet 做人工审阅。它还有 Config Doctor、Provider Capability fallback、Trace Viewer、Experience to Skill、固定 smoke eval、baseline compare 和 release-check。我的目标是把 Agent 从“能调用工具”推进到“能被工程团队安全演示、排查和发布”。

## 3 分钟版本

Ricbot 的出发点是：很多 Agent demo 能跑一次，但很难解释为什么跑、怎么排查、怎么控制风险、怎么证明没有回归。我用 Java 17 做了一个完整 runtime，把 agent 执行拆成几个边界。

入口层支持 CLI、OpenAI-compatible API、企业 IM webhook 和 Console。执行层用 AgentLoop 管会话、上下文、工具和 trace，用 AgentRunner 做单次模型-工具循环。工具层有 ToolRegistry，既能注册本地文件/搜索/执行等内置工具，也能把 MCP tools/resources/prompts 包装成统一工具。安全上，文件和命令工具受 workspace、SSRF、审批和风险策略限制。

复杂任务通过 Team 模块执行，可以开启 `--worktree --verify`。它会在受管 git worktree 里让 worker 执行，再由 verifier 验证，最后通过 Workspace diff 和 ChangeSet 收口给人审阅，避免 Agent 直接污染主工作区。

可观测性上，Config Doctor 解释 provider、API key、tools、MCP 和 capability；Trace Viewer 展示模型请求、工具调用和运行事件；Console 把 config、team、workspace、trace、experience、eval、release-check、tools/MCP 聚合起来。评测上，用 deterministic smoke provider 跑 golden scenarios，并用 baseline compare 抓 pass -> fail 回归。

我还加了 Provider Capability Override 和 MCP Diagnostics，解决 OpenAI-compatible 中转、私有模型和 MCP 工具暴露不透明的问题。整体来说，Ricbot 展示的是一个 Agent Platform 的工程化骨架。

## STAR 版本

**Situation**: 我发现很多 Agent 项目停留在“能调用模型和工具”的 demo 阶段，但缺少配置诊断、执行可观测性、变更隔离、评测门禁和安全边界。

**Task**: 我想做一个能在面试、演示和开源主页上讲清楚的 Agent Runtime，证明自己能把 Agent 从原型推进到工程化系统。

**Action**: 我用 Java 17 构建了 Ricbot。核心包括 AgentLoop/AgentRunner、ToolRegistry/MCP、Provider Capability、Team worktree、Workspace/ChangeSet、Experience to Skill、Eval baseline、release-check、Console 和企业 IM webhook。每个模块都围绕“可诊断、可回放、可治理”设计。

**Result**: 最终形成了完整闭环：`config doctor -> team run --worktree --verify -> workspace diff -> change create -> trace -> experience -> eval smoke -> release-check -> console`。这个项目可以用 5-8 分钟演示，也能拆成后端架构、AI Infra、Agent safety、MCP diagnostics 等面试话题。

## Interview Follow-Ups

### 为什么要做 capability？

因为 OpenAI-compatible 不等于能力完全一致。中转网关、私有模型和同名模型可能在 tool calling、streaming、vision、JSON mode 上表现不同。Capability 让运行时能保守降级：明确 `false` 才降级，`UNKNOWN` 保持现有行为；同时允许用户用 `model_capabilities` 覆盖本地事实。

### 为什么要 worktree？

Agent 写代码时最大风险是污染主工作区。受管 worktree 把任务执行和主分支隔离开，失败时可以保留现场，成功后也要通过 diff 和 ChangeSet 给人审阅。这比让 Agent 直接改主目录更适合工程流程。

### 怎么防止工具乱执行？

几层边界一起做：ToolRegistry 统一工具入口；文件/命令工具受 workspace 限制；web 工具有 SSRF 防护；高风险命令走审批；Console 写操作有 auth、Origin/Referer、rate limit 和 audit；Provider capability 还能在模型不支持 tool calling 时不暴露 tools。

### Eval 怎么评估 Agent？

Ricbot 的 smoke eval 不依赖真实模型，而是 deterministic provider 回放固定场景，验证 agent loop、工具调用、上下文和错误处理路径。Baseline compare 关注行为回归，特别是 pass -> fail。它不是完整质量评估，但适合作为发布前稳定门禁。

### Console 写操作怎么保证安全？

Console 默认本地使用。POST 复用 bearer auth，检查 Origin/Referer，有轻量 rate limit，敏感字段脱敏，写操作审计到 `console-actions.jsonl`。而且 Console 不开放任意命令，只开放 verify/reject/promote、approval、受管 worktree change-create/discard 和固定 smoke eval 这类受控动作。

### MCP 怎么诊断？

MCP Diagnostics 输出 server transport/status、registered/filtered tools、enabled_tools 过滤解释、schema summary、schema hash 和 redacted lastError。它回答两个问题：server 有没有连上，以及哪些 MCP tool 最终真的暴露给模型。Console 不通过 diagnostics 启停 server，也不调用 MCP tool。
