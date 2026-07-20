# Durable Agent Runtime Upgrade

本次升级把 Ricbot 从“可恢复的本地 ReAct runner”推进为有显式状态、历史恢复、副作用协议、隔离后端和持久协作原语的 agent runtime。

## Runtime invariants

1. journal event sequence 在单个 `(sessionKey, runId)` 内从 1 连续递增。
2. event 是事实源，`state.json` 只是可丢弃的 materialized view。
3. 模型响应和工具批次完成后都生成 checkpoint；checkpoint 记录对应 journal sequence 和 session baseline message count。
4. 工具副作用发生前必须先持久化 RUNNING invocation 和 side-effect reservation。
5. 未知副作用不自动重放；相同幂等键不能绑定不同 session、tool 或参数摘要。
6. fork 必须命中真实 event sequence，并创建不同的 child run identity；跨 session fork 会持久化独立 child session。

## Storage layout

```text
.ricbot/
  run-journal/<session-hash>/<run-hash>/events/*.json
  run-checkpoints/<session-hash>.json
  run-checkpoints/history/<session-hash>/<checkpoint-hash>.json
  side-effects/<idempotency-key-hash>.json
  shared-state/<namespace-hash>/<key-hash>.json
  team-runtime/<team-hash>/workers/<worker-hash>/
  worker.json
  inbox/*.json
  acks/*.json
```

所有外部 identity 都经过 SHA-256 后用于路径，原值保留在 JSON 内并在读取时校验。文件后端用于单机部署；多进程/多节点部署应以相同接口替换为共享数据库或对象存储。

## Recovery and fork

- `AgentGraphRuntime` 提供可注册节点、条件边、暂停和 cursor 恢复；默认 graph 保持 `MODEL -> TOOLS -> MODEL/TERMINAL` 兼容行为。
- `RunRecoveryCoordinator` 处理进程中断后的 pending tool calls。
- `RunResumeService.at(...)` 在指定 event sequence 选择不晚于该位置的 checkpoint，并重建 session baseline + run messages。
- `forkAt(...)` 创建 child journal、child session 和 lineage metadata。
- `ExecutableRunFork.applyTo(...)` 把 child state、消息和 checkpoint 注入 `AgentRunSpec`。若 checkpoint 位于模型响应之后，Runner 会先精确完成其中尚未执行的只读工具，再请求下一次模型；副作用工具保持 fail closed。

## Side effects

工具默认仍可直接实现 `execute`。需要更强语义时可：

- 从 `ToolExecutionContext.idempotencyKey()` 向外部 API 透传幂等键；
- 覆盖 `supportsCompensation()`；
- 实现 `compensate(params, previousResult, context)`。

补偿要求原始参数摘要一致且携带一次性 approval id。`SideEffectApplicationService` 提供 retry/compensation 控制面，审批必须与幂等键和动作绑定；所有 ledger 状态迁移都写入 trace。补偿不是自动 rollback；它是有审计信息的显式动作。

## Execution backends

- `LocalExecutionBackend`：宿主进程、并发 drain stdout/stderr、输出上限、超时后终止进程树。
- `DockerExecutionBackend`：默认 `--network none`，只把请求工作目录挂载到 `/workspace`。
- `RemoteExecutionBackend`：transport-neutral adapter，供 E2B、OpenSandbox 或 Kubernetes client 接入。

backend 不会静默降级。`ExecutionBackendRegistry.select` 只有在 `allowFallback=true` 时才选择 fallback，并返回降级原因。

`tools.exec.backend` 可设为 `local` 或 `docker`；Docker image、网络和显式 fallback 均由配置控制，最终选择写入 execution metadata。

## Shared storage, retrieval and telemetry

- `AgentPersistenceFactory` 用 `RICBOT_PERSISTENCE_BACKEND=shared-file`（或 `-Dricbot.persistence.backend=shared-file`）把 session、journal、checkpoint 和 side-effect ledger 一致切到 CAS shared store adapter。
- RAG 默认继续使用离线 hashing embedding；显式设置 `RICBOT_EMBEDDING_PROVIDER=openai`、`RICBOT_EMBEDDING_API_KEY`、`RICBOT_EMBEDDING_MODEL` 和可选 `RICBOT_EMBEDDING_BASE_URL` 后使用真实 OpenAI-compatible embeddings。向量按 tenant/model/chunk fingerprint 持久化。
- 配置 `OTEL_EXPORTER_OTLP_ENDPOINT`、`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` 或 `RICBOT_OTLP_ENDPOINT` 后启用 OTLP batch exporter；未配置时不创建 exporter/background thread。
- `ricbot eval matrix --spec matrix.json --scenarios scenarios.jsonl` 运行真实 provider/model 矩阵，并输出通过率、P95、token 成本和长轨迹报告。

## Compatibility

- 原有 `AgentRunSpec`、`AgentExecutionService` 和 `ExecTool` 构造路径保留，新增能力都有 disabled/local 默认值。
- 旧 checkpoint 缺少 journal/node 字段时按零值和 MODEL 节点读取；schema 仍保持向后兼容。
- 原有 workspace RAG 路径作为 `default` tenant 保留；只有显式 tenant 才写入 `.rag/tenants/<hash>`。
- OpenTelemetry exporter 是显式 opt-in；没有 endpoint 时是 no-op，不影响 journal。

## Production follow-up

文件 shared store 提供正确的单机多进程 CAS；真正多节点水平扩展仍需提供 SQL/Redis/Object Storage `SharedStateStore` 实现，并让 API 节点使用相同 message bus。Remote execution adapter 也需要部署方提供实际 client、凭据和配额策略。
