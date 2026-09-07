# Ricbot Durable Runtime v6 完整演示

本指南在可丢弃的独立 Git 仓库中演示 v6 的单一 Durable Agent Runtime：真实模型调用、工具 Effect、审批等待、Child Run、State Replay、Execution Replay、取消、ArtifactDelta 和验证。

## 1. 准备

要求 JDK 17，并在 Ricbot 仓库根目录执行：

```bash
sh scripts/prepare-full-runtime-demo.sh
sh ./mvnw -q -DskipTests package
cd target/ricbot-full-demo
```

配置文件来自 `examples/order-fulfillment-demo/ricbot.demo.config.json`。如需真实模型，在环境变量中设置该配置引用的 API Key；固定 Eval 不需要真实凭据。

先运行诊断：

```bash
java -jar ../Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c ricbot.demo.config.json
```

## 2. 启动 Run

进入交互 CLI：

```bash
java -jar ../Ricbot-1.0-SNAPSHOT.jar agent \
  -c ricbot.demo.config.json
```

创建普通 Run：

```text
/run start 为订单履约 CLI 增加 CSV 批量导入、库存原子预留、幂等处理、dry-run、JSONL 审计、可注入 Clock、错误行报告及单元/集成测试；读取完整审计规则，并在修改后运行验证。
```

v6 没有 `--mode team`。模型在策略允许时可调用内建 Runtime Control `spawn_child_runs`，由固定 `DELEGATE` 阶段原子创建普通 Child Run。例如：

```json
{
  "children": [
    {"key": "implementation", "goal": "实现订单导入与库存预留"},
    {"key": "tests", "goal": "补齐并运行回归测试", "dependsOn": ["implementation"]}
  ],
  "waitForAll": true
}
```

`dependsOn` 只能引用当前批次中更早声明的 key。Runtime 根据父 Run、调用 ID 和批次位置生成确定性 Child ID，并在父提交中同时写入 Child、relation、下一 Activation 和 `ChildRunWait`。查看关系与时间线：

```text
/run list
/run show <runId>
/run children <runId>
/run events <runId>
/run timeline <runId>
```

Child 的依赖存储为 Run relation；依赖完成前不会生成可领取 Activation。父 Run 的 `ChildRunWait` 只有在整个 Join 集合满足后才恢复，每个 Child 只投递一次完成事件。

通过 `RunSpec.metadata` 约束运行策略：

- `allowChildRuns`：是否向模型暴露 `spawn_child_runs`，默认 `true`。
- `maxChildRuns`：单个父 Run 的 Child 总数上限，默认 8，范围 0–64。
- `maxChildDepth`：委派深度上限，默认 4，范围 0–16；`delegationDepth` 由 Runtime 维护并传给 Child。
- `maxModelAttempts`：一次 MODEL Superstep 的总 attempt 上限，默认 3，范围 1–10。
- `providerRetryMode`：`standard` 会把可重试失败或未能 reconcile 的未知结果提交为持久 `RetryWait`；`manual`/`never` 会在 reconcile 仍无结果时 fail closed。

这些策略元数据由 Child 继承。重试不使用内存 sleep；Scheduler 到期后写入去重的 `TimerExpired`，再创建新的持久 Model Invocation attempt。

## 3. 审批与 Effect

写工具先形成 `EffectIntent`，包含参数摘要、幂等键、资源声明、授权证据和 reconcile 策略。需要审批时 Run 进入 `WAITING(ApprovalWait)`：

```text
/approve <requestId>
# 或
/reject <requestId>
```

审批命令只提交 `ApprovalDecision`；不会直接恢复旧执行栈。写 Effect 的状态为：

```text
PREPARED → DISPATCHING → SUCCEEDED | FAILED | UNKNOWN
```

恢复遇到 `UNKNOWN` 时先调用工具的 `reconcile`。仍不能确认时进入 `WAITING(ExternalEventWait: EffectConfirmation)`，不会自动重放写操作。取消同样先持久化；存在未确认写 Effect 时，Run 要等 reconcile 或人工确认后才能成为 `CANCELLED`。

人工确认统一使用 Effect Ledger 命令：

```text
/run effect-confirm <runId> <effectId> <succeeded|failed> [resultReference]
```

每次模型 dispatch 前，`CONTEXT` 会用最终消息、工具 schema、动态提示和输出 reserve 编译请求，将摘要与非零 token 预留写入 Model Invocation Ledger。超过上下文阈值时进入 `COMPACT`，推进 `compactedThroughCursor` 后重新编译；根预算预留在 SQLite 事务中跨 Child Run 原子校验。Provider request ID 先于网络 dispatch 持久化，取消则在事件落库后尽力调用 provider cancel 并中断活动调用。

`manage_tool_groups` 接受完整目标集合，例如：

```json
{"active_groups":["basic","coding"]}
```

工具曝光与 FileReadReceipt 通过 `ToolStateMutation` 写入 Run channel；工具流式输出和进度只作为临时 Observation。

## 4. Replay 与 Fork

只读状态回放：

```text
/run replay <runId>
/run replay <runId> <commit>
```

State Replay 从 origin 和有序的已提交写重新运行纯 Reducer，不调用模型、工具或 Artifact 适配器。

创建 Execution Replay：

```text
/run fork <runId> <commit> <newRunId>
```

默认 Fork 进入确认等待；批准后根 Run 与克隆的未完成后代一起解锁。立即执行必须显式指定：

```text
/run fork <runId> <commit> <newRunId> --execute
```

Fork 会复制对应 Transcript 前缀，把已完成 Child 保存为只读事实引用，递归克隆未完成后代，并重写克隆子树内部依赖。指定 commit 之后才创建的后代不会进入 Fork；子树中存在未解决 `UNKNOWN` Effect 时拒绝 Fork。

## 5. Artifact 与变更收口

Git/worktree 只是 `ArtifactDelta`、`ArtifactIntegrator` 和 `Verifier` 的首个外围适配器。查看工作区和 ChangeSet：

```text
/workspace
/change
```

ChangeSet、审批和验证报告保存在 `.ricbot/application.db`；提交或回滚仍通过普通 v6 Run 和 Effect Runtime 执行，不绕过授权与副作用账本。

## 6. 数据库与恢复检查

核心数据库：

```text
.ricbot/runtime.db
```

只包含 schema v3 Runtime 事实：Run、Activation、Commit/ChannelWrite、Inbox、Model Invocation、Effect、Resource Lease、Run Relation 和 Runtime Event。

外围数据库：

```text
.ricbot/application.db
```

保存 Session、Transcript、Trace、Approval、ChangeSet 和 Verifier Report。

若启动时发现受支持的 v5/schema v2 核心库，Runtime 会先确认没有活跃 lease、执行 WAL checkpoint，再归档到：

```text
.ricbot/archive/runtime-v5-<timestamp>-<digest>/
```

目录中的 `manifest.json` 记录原路径、schema 版本、SHA-256 和归档时间。未知、损坏或仍被使用的数据库不会被移动。

## 7. 验收

回到 Ricbot 仓库运行：

```bash
sh ./mvnw -q test
sh ./mvnw -q -DskipTests package
sh scripts/release-check.sh
```

验收重点：

- State Replay 的最终摘要与实时投影一致，且没有模型、工具或 Artifact 写入。
- 模型已观察响应在崩溃后复用；写 Effect 的 `UNKNOWN` 不会静默重放。
- Activation 和 ResourceClaim 的 SQLite CAS/租约在多连接下保持互斥并可过期恢复。
- Child 完成与父 Inbox 事件同事务提交，Join 不会提前唤醒。
- 核心状态与事件表中没有 streaming、thinking delta 或 tool progress。
- CLI 中没有 `--mode team` 或 `/task` 双轨入口。
