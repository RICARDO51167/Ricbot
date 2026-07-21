# Ricbot Architecture

Ricbot 是 CLI-first Java Agent Runtime。架构只围绕五个问题组织：长任务如何持久执行、崩溃后如何精确恢复、副作用如何安全提交、多个 Worker 如何协作、代码修改如何隔离和验收。

## Layers

```text
CLI
  -> AgentLoop / AgentRunner / Agent Graph
  -> Journal / Checkpoint / Side Effect Ledger
  -> Tool Registry / Provider / Context / Memory
  -> Worker / Team / Mailbox
  -> Workspace / ChangeSet / Verification
  -> Eval / Release Gate
```

## Agent Graph

`AgentGraphRuntime` 管理节点、条件边、暂停和 cursor。默认图维持 `MODEL -> TOOLS -> MODEL/TERMINAL` 行为，但执行状态不依赖固定 ReAct 循环。

`AgentRunner` 是兼容入口，单次 Graph Run 由 `GraphRunService` 执行。模型响应、工具边界和节点迁移写入 Journal；Checkpoint 保存节点、消息、阶段和 Journal sequence。

## Durable Runtime

三条持久化链承担不同事实：

- `RunJournalStore`：单调序号的执行事实，可重建 `RunState`。
- `RunCheckpointStore`：不可变历史版本和 latest pointer，用于 Resume 与 Fork。
- `SideEffectStore`：副作用 reservation、结果、重试授权和补偿状态。

恢复规则默认 Fail-Closed：

- 已完成工具结果可复用。
- 参数和策略完全一致的未知只读工具可以重试。
- 未知副作用必须获得新的显式授权。
- 断线客户端通过 exclusive cursor 补发 Run Event。

文件实现和共享实现遵循同一套接口。共享实现基于 `SharedStateStore` 的 CAS 语义，避免跨进程覆盖。

## Side Effect Safety

写文件、命令执行和其他副作用工具统一经过：

```text
Risk Analysis
  -> Approval
  -> Idempotency Reservation
  -> Execute
  -> Persist Outcome
  -> Retry Authorization / Compensation
```

幂等身份绑定 Session、Tool 和规范化参数。未知状态不会自动当作成功，也不会无审批重复执行。

## Worker and Team

`WorkerRuntime` 保存 Worker 规范和生命周期。Team 在其上提供任务、角色和验证语义。

协作事实包括：

- 持久 Worker 状态；
- Mailbox sequence 与 cursor；
- Ack、Broadcast、Join、Handoff、Cancel；
- 重启后的未读消息和任务恢复；
- Worker 与 Verifier 结果回传。

`TeamEngine` 是应用编排入口。Worker 执行复用 Agent Runtime，不创建第二套 Agent 框架。

## Workspace and Verification

Workspace 支持 Local 和受管 Git worktree。Team 任务可以在独立 worktree 中修改文件，主工作区不会直接收到未审阅变更。

```text
Team Task
  -> Managed Worktree
  -> Restricted AgentRun
  -> Verification
  -> Workspace Diff
  -> ChangeSet Review
```

ChangeSet 是人工审阅边界，不自动 Merge 或 Commit。

## Context and Memory

Context 由 Session、Workspace、Policy 与 Memory 组成。结构化 Memory 只召回已审批条目，并通过 Tenant ID 隔离 working、episodic、semantic 和 perceptual 数据。

Memory 写入必须经过显式策略；模型推断和低置信度内容不能自动晋升为长期事实。

## Provider and Tools

Provider 保留 OpenAI-compatible 和 Anthropic。Capability 由静态规则、启发式与用户 Override 合并；`false` 触发明确降级，`UNKNOWN` 保持原行为。

核心工具包括文件读取、写入、编辑、目录、Glob、Grep、Exec、Spawn 和 Diff Review。所有写工具共享审批、风险和副作用协议。

执行后端只保留 Local 与 Docker。Docker 默认断网且只挂载允许的 Workspace，失败时不静默回退。

## Eval and Release Gate

Eval 提供 deterministic Smoke、Matrix、Baseline、Compare 与 Replay。Release Gate 串联测试、打包、Config Doctor、Smoke 和回归比较。

Eval Artifact 是验证证据，不是运行事实源。真实 Run 状态只能从 Journal、Checkpoint、Worker Store、Mailbox 和 Side Effect Ledger 重建。

## Invariants

- Pending Tools 可以从 Checkpoint 精确恢复。
- 未知副作用保持 Fail-Closed。
- 幂等键不能跨 Session、Tool 或参数复用。
- Worker 重启后 Mailbox、Ack、Join、Handoff 正常。
- Docker 默认断网且失败不静默回退。
- Journal sequence 连续并可重建状态。
- Worktree 修改不泄漏到主工作区。
- Trace、Telemetry 和报告只能作为投影。
