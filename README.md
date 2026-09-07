# Ricbot

Ricbot 是一个 CLI-first、Java 17 的 Durable Agent Runtime。v6 只有一个执行内核：所有普通调用、委派、重试和变更操作都是 Run；不再存在 Agent/Team 双内核、业务 Graph 扩展点或 Task Scheduler。

## v6 核心

- 固定阶段：`INGEST → CONTEXT → MODEL → TOOLS / COMPACT / DELEGATE / TERMINAL`；暂停统一进入 `WAIT`。
- 纯 `RuntimeReducer` 只根据当前 `RunState`、`ChannelWrite` 和 `RuntimeCommand` 计算下一状态。
- Activation 带持久租约；只有原子提交成功才推进 Superstep。
- `maxSupersteps` 是整个 Run 生命周期的持久上限；等待恢复和进程重启不会重置。耗尽时以 `MAX_SUPERSTEPS_EXCEEDED` 进入 `FAILED`。Fork 从 0 重新计数，并记录源 Run 与源 superstep；每个 Child Run 独立计数。
- 模型调用进入 Model Invocation Ledger；写工具和 Artifact 集成进入 Effect Ledger。
- `CONTEXT` 在每次调用前编译完整请求并持久化摘要、token 估算和预算预留；超阈值时由 `COMPACT` 推进 durable cursor，实际改变下一次模型输入。
- Provider request ID 在 dispatch 前落账；取消事件持久化后会尽力调用 provider cancel 并中断正在执行的模型或工具线程。
- `UNKNOWN` 写 Effect 只允许 reconcile 或人工 `EffectConfirmation`，不会静默重放。
- 委派产生普通 Child Run；依赖、Join、Retry 与父级唤醒都由 Run relation 和 Inbox 表达。
- State Replay 只重放已提交的写和事件，不调用模型、工具或 Artifact 适配器。
- Streaming、thinking delta 和工具进度只是进程内 Observation，不写入 Run State。

## 存储边界

- `.ricbot/runtime.db`：schema v3 核心事实，包括全局事务序号、Run、Activation、Commit、Inbox、Model Invocation、Effect、Resource Lease、根预算预留、Run Relation 和 Runtime Event。
- `.ricbot/application.db`：Session、Transcript、Trace、Approval、ChangeSet 和 Verifier Report 等外围数据。

启动发现受支持的 schema v2/v5 Runtime 数据库时，会在确认没有活跃 lease 并完成 WAL checkpoint 后，将原文件归档到 `.ricbot/archive/runtime-v5-<timestamp>-<digest>/`，写入 manifest，再创建空 schema v3 数据库。

兼容性风险：已知的旧 v3 布局在确认没有活跃 Activation/Resource lease 后会记录破坏性升级警告，并**无备份永久删除历史 Run、等待状态与 UNKNOWN Effect 后重建**。这是本版本明确接受、尚未修复的数据丢失风险；不可恢复。未知布局、指纹不匹配、损坏或仍在使用的数据库仍 fail closed。

## 构建与验证

```bash
sh ./mvnw -q test
sh ./mvnw -q -DskipTests package
sh scripts/smoke.sh
sh scripts/release-check.sh
```

发布门禁覆盖 schema v3/归档、Runtime 边界、Durable Runtime 回归、打包、Config Doctor、固定 Eval Smoke、Replay 和 Baseline Compare。报告写入 `target/release-check-report.md`。

固定、脱敏基线位于 `evals/baselines/golden/`。`sh scripts/eval-baseline.sh create` 只在 `target/eval-baseline-candidate/` 生成候选；审查后必须显式执行 `promote --from ... --force` 才会更新受版本管理的基线。

## CLI

单次调用：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  --message "分析当前仓库并给出修改建议" \
  --session demo
```

交互模式：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent
```

Run 命令：

```text
/run start <goal>
/run list
/run health
/run show <runId>
/run events <runId>
/run timeline <runId>
/run children <runId>
/run cancel <runId>
/run effect-confirm <runId> <effectId> <succeeded|failed> [resultReference]
/run retry <runId>
/run replay <runId> [commit]
/run fork <runId> [commit] [newRunId] [--execute]
/approve <requestId>
/reject <requestId>
/workspace
/change
/trace
```

`/run replay` 仅执行只读 State Replay。需要继续执行历史状态时使用 `/run fork`；默认 Fork 等待确认，只有 `--execute` 才立即调度。Retry 总是创建带 `retryOfRunId` 的新 Run，终态 Run 不会原地重开。`effect-confirm` 是 UNKNOWN Effect 的唯一人工确认入口。

Child Run 是唯一委派机制。模型可在策略允许时调用内建 Runtime Control `spawn_child_runs`；每个 Child 使用相同的生命周期、Effect、Replay、Cancel 和 Checkpoint 规则。CLI 不再提供 `--mode team` 或 `/task` 命令。调用参数示例：

```json
{
  "children": [
    {"key": "inspect", "goal": "检查输入与约束"},
    {"key": "verify", "goal": "验证实现结果", "dependsOn": ["inspect"]}
  ],
  "waitForAll": true
}
```

`dependsOn` 只能引用同一批次中更早声明的 key；整个批次在父 Run 的一次提交中原子创建。`RunSpec.metadata` 的运行策略键包括：`allowChildRuns`（默认 `true`）、`maxChildRuns`（默认 8，范围 0–64）、`maxChildDepth`（默认 4，范围 0–16）和 `maxModelAttempts`（默认 3，范围 1–10）。`providerRetryMode=standard` 使用持久 `RetryWait`；`manual` 或 `never` 在 reconcile 仍不能确定模型结果时 fail closed。Child 自动继承策略元数据，`delegationDepth` 由 Runtime 维护。

`/run health` 返回调度器运行状态、最近成功/失败时间、连续失败次数、当前退避和最近批量。调度器有工作时按 100 ms 轮询，空闲时指数退避到 5 s，并可由新事件唤醒；每次 READY/Retry 查询最多读取 64 个 Run。

显式 `-c/--config` 文件缺失或损坏分别返回稳定错误 `CONFIG_NOT_FOUND`、`CONFIG_INVALID`。未显式指定且默认路径不存在时才使用默认配置；默认路径存在但损坏同样失败。`config doctor` 的文本与 JSON 会为关键设置输出 `DEFAULT`、`FILE`、`ENV` 或 `CLI_OVERRIDE` 来源，但不会输出凭据内容。

## 安全边界

- 文件、命令和网络工具受 workspace、审批及风险策略限制。
- 文件写入采用“仅创建”或“匹配 SHA 后更新”，Ricbot 写入者按规范路径串行化并在替换前复检。它不能为不遵守该锁协议的任意外部进程提供绝对文件系统 CAS；最后一个非协作竞争窗口仍是已记录边界。
- 多资源写 Effect 使用排序后的 SQLite lease；执行期间续租。
- Cancel 先持久化，再尽力取消；未确认的写 Effect 会阻止最终 `CANCELLED`。
- Docker 默认断网，失败不会静默回退到 Local。
- Git/worktree 通过 `ArtifactDelta → ArtifactIntegrator → Effect Runtime` 外围适配器执行，不属于 Runtime 内核。

完整演示见 [docs/guides/full-runtime-demo.md](docs/guides/full-runtime-demo.md)。

## License

仓库当前未声明正式开源 License。公开发布前需要补充明确的 License。
