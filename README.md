# Ricbot

Ricbot 是一个面向长任务与多智能体协作的 CLI-first Java Agent Runtime。核心能力是持久执行、精确恢复、副作用安全、Worker 协作和隔离工作区；唯一对外入口是 CLI。

## 核心能力

- Unified Agent Graph：`INGEST → CONTEXT → COMPACT? → MODEL → TOOLS/APPROVAL → STEERING → CONTEXT`，支持 Superstep、类型化 Channel、确定性 Reducer、暂停与恢复。
- Durable Run：`.ricbot/runtime.db` 中的版本化事件事实、原子投影、Replay Digest 校验与安全提交点 Fork。
- Side Effect Safety：`RESERVED → EXECUTING → SUCCEEDED/FAILED/UNKNOWN`，幂等领取、审批 Signal、失败关闭和人工重试授权。
- Multi-Agent：持久 Task DAG、统一 LocalTaskScheduler、Fan-out/Join、Cancel/Recover 与父 Run 自动唤醒。
- Workspace Isolation：Local workspace、受管 Git worktree、Diff、ChangeSet 和 Verification。
- Memory：结构化长期记忆、会话摘要、历史召回和 Tenant 隔离。
- Execution：Local 与 Docker 后端；Docker 默认断网，不允许静默回退。
- Eval：deterministic smoke、matrix、baseline、compare、replay 和发布门禁。

## 核心闭环

```text
/run start <goal> --mode team --worktree --verify
  -> LeaderPlan -> Worker Tasks -> Join -> ApplyChangeSets -> Verifier
  -> /run report <runId>
  -> /task list <runId>
  -> /workspace
  -> /change
```

Worker 在受管 worktree 中通过受限 AgentRun 执行，Verifier 检查结果，ChangeSet 作为人工审阅边界。Ricbot 不会自动把未审阅修改合并或提交到主工作区。

Runtime v4 的真实 Qwen 端到端演示见 [docs/guides/full-runtime-demo.md](docs/guides/full-runtime-demo.md)。演示使用 `examples/order-fulfillment-demo/` 种子，并由 `scripts/prepare-full-runtime-demo.sh` 创建不污染本仓库的独立 Git 工作区。

## 环境要求

- JDK 17+
- 仓库内 Maven Wrapper
- 可选的模型 API Key；固定 Smoke Eval 不需要外网或真实模型

## 构建与验证

```bash
sh ./mvnw -q test
sh ./mvnw -q -DskipTests package
sh scripts/smoke.sh
```

发布门禁：

```bash
sh scripts/release-check.sh
```

门禁依次执行测试、架构硬切检查、Team Runtime Golden、打包、Config Doctor、固定 Smoke Eval 和 Baseline Compare。Baseline 缺失会直接失败；报告写入 `target/release-check-report.md`。

## 配置诊断

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json
```

Config Doctor 检查 workspace、Provider、模型能力、API Key 和执行后端，并对敏感字段脱敏。

## CLI

单次 Agent 调用：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  --message "分析当前仓库并给出修改建议" \
  --session demo
```

交互模式：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent
```

统一 Runtime 主要命令包括：

```text
/run start <goal> [--mode agent|team] [--worktree] [--verify]
/run list
/run status|report|graph|events|resume|cancel <runId>
/run replay <runId> [eventSequence]
/run fork <runId> [eventSequence] [newRunId]
/task list <runId>
/task show|retry|cancel <taskId>
/side-effect list
/side-effect show|retry <idempotencyKey>
/approve <requestId>
/reject <requestId>
/workspace
/change
/trace
```

CLI 能力边界：

| 操作 | 当前入口 | 覆盖情况 |
|---|---|---|
| 创建 | 普通消息、`/run start` | 已覆盖 |
| 恢复 | 启动扫描、父 Run Wake、`/run resume <runId>` | 已覆盖 |
| 审批 | `/approve <requestId>`、`/reject <requestId>`、`/change approve` | 已覆盖 |
| Worker 协作 | `/run start --mode team`、`/task`、`/run report` | Task DAG、Delivery Outbox 与后台唤醒统一由 Runtime 管理 |
| Memory | Agent 上下文自动召回结构化 Memory 和历史摘要 | 仅运行时使用；没有治理命令 |
| Eval | `eval`、`lint`、`smoke`、`matrix`、`compare`、`replay` | 已覆盖 |

Run、Session、Task、Delivery、Approval 与 SideEffect 统一写入 SQLite WAL 数据库 `.ricbot/runtime.db`。Ricbot 只接受空数据库或当前 Schema v2；旧版本及未知版本会直接拒绝启动，且不会改写、迁移或归档原文件。`/run replay` 只回放当前数据库中的 Run。

进程通过 5 秒 heartbeat 和 30 秒 lease 注册实例；Activation、Task 与 SideEffect 均以 owner、lease、version 和 CAS 领取。只有租约过期且原 owner 已确认死亡的 `EXECUTING` 副作用才会转入 `UNKNOWN`。Runtime 退避只使用数据库 `availableAt`，执行路径不进行内存睡眠。

## Eval

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval lint \
  --scenarios evals/golden.jsonl

java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-smoke-workspace \
  --out target/eval-smoke-artifacts

java -jar target/Ricbot-1.0-SNAPSHOT.jar eval matrix \
  --spec config/examples/eval-matrix.json
```

固定 Smoke 使用 deterministic Provider，不访问真实模型。Matrix 聚合通过率、长轨迹覆盖、P50/P95、Token、工具调用、失败类型和估算成本。

## 安全边界

- 未知副作用保持 Fail-Closed。
- 幂等键不能跨 Session、Tool 或参数复用。
- 文件、命令和网络工具受 workspace、审批与风险策略限制。
- Docker 默认断网，后端失败不会静默回退到 Local。
- Worktree 修改通过 Diff 与 ChangeSet 收口。
- Memory 只召回当前结构化内容和历史摘要；租户数据相互隔离。
- Trace、Telemetry 和报告由统一事件投影，不能覆盖 Runtime 事件事实。

## 文档

- [架构与边界](docs/architecture/ricbot-architecture.md)
- 各有效目录内的 `README.md`：说明目录职责、子目录和直接文件；空目录、生成目录、IDE 元数据与 `.git` 不生成说明。

## License

仓库当前未声明正式开源 License。公开发布前需要补充明确的 License。
