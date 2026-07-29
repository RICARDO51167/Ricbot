# Ricbot Agent Runtime v4：完整真实模型演示

本指南在一个可丢弃的独立 Git 仓库中演示 Runtime v4 的完整闭环：真实 DashScope/Qwen、单 Agent、Session Memory、TeamPlan、Task DAG、隔离 worktree、累计预算、Artifact Offload、Context Compression、审批暂停/恢复、Verifier、ChangeSet、Replay、Fork、Cancel、Rollback 和低预算收尾。

> 费用和安全：演示会调用真实模型。先在 DashScope 控制台设置账户限额；模型价格因地域、输入长度、缓存和优惠变化，以账单与[阿里云模型价格页](https://help.aliyun.com/en/model-studio/model-pricing)为准。示例模型卡使用新加坡 `qwen3.6-plus` 的演示估值 USD 0.48/百万输入 Token、USD 1.44/百万输出 Token；运行前必须核对适用于你的地域和合同的价格。官方计费公式为输入 Token 费用加输出 Token 费用，工具定义本身也会进入 Token 计费。

## 1. 准备一次性工程

在 Ricbot 仓库根目录执行：

```bash
sh scripts/prepare-full-runtime-demo.sh
sh ./mvnw -q -DskipTests package
```

脚本拒绝覆盖已有目录，并创建：

- `target/ricbot-full-demo/`：独立 Git 仓库；
- `rules/fulfillment-audit-rules.md`：约 420 条重复但可检索的完整审计约束，用于稳定触发 Offload/Compression；
- `fixtures/orders.csv`：含正常、重复、格式错误和缺货行；
- `ricbot.demo.config.json` 与 JSON v1 模型卡。

确认基线：

```bash
git -C target/ricbot-full-demo status --short
sh ./mvnw -q -f target/ricbot-full-demo/pom.xml test
```

预期：Git 状态为空，种子测试通过。

## 2. API Key、模型卡、预算与受控 Exec

获取 DashScope API Key 后只放入环境变量，不写进仓库：

```bash
export DASHSCOPE_API_KEY='替换为真实 Key'
```

演示配置的关键行为：

- 根 Run：120,000 Token、500,000 微美元（USD 0.50）、1,800 秒活跃时间、180 次工具调用；
- `finalization_tokens=4096` 永远保留给无工具收尾；
- TeamPlan 固化后，未显式指定 Worker 上限时按 Worker 数量等分本地额度；所有 Worker 仍通过根事件流共同受根硬上限约束；
- `max_tool_result_chars=900`、Artifact 预览 500 字符、单次读取 4,000 字符；
- Exec 已启用、要求审批、限制在演示 Workspace，且不允许后端静默回退；
- 模型卡路径优先于内置通配卡。费用预算要求模型卡同时给出输入和输出价格，否则 Config Doctor 与启动都会失败。

如果你的账户模型名不同，同时修改配置的 `model` 和模型卡的 `modelPattern`。如果账单币种不是 USD，先按你认可的固定换算规则写入 USD/百万 Token，并在模型卡 `source` 中记录汇率日期；Runtime 不会猜汇率。

运行诊断：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c target/ricbot-full-demo/ricbot.demo.config.json --json
```

验收：无 `MISSING_MODEL_PRICE`、`INVALID_MODEL_CARD`、缺失 Key 或 Exec sandbox 错误。macOS 若本机不支持受控 sandbox，请改用已配置的 Docker 后端；不要为了演示关闭 Workspace 限制。

## 3. 单 Agent、Session Memory 与上下文来源

先做一次真实单 Agent 调用：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c target/ricbot-full-demo/ricbot.demo.config.json \
  --session fulfillment-demo \
  --message "只读分析订单履约种子工程，列出当前缺口；必须读取 rules/fulfillment-audit-rules.md，并引用 RULE-0001、RULE-0210、RULE-0420。"
```

随后进入同一 Session 的交互模式：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c target/ricbot-full-demo/ricbot.demo.config.json \
  --session fulfillment-demo
```

依次输入：

```text
/context
/summary
/status
刚才审计规则中间位置的编号是什么？请说明答案来自哪种上下文来源。
```

验收：`/context` 展示结构化来源；后续轮次能够使用 Session 历史/摘要；`/status` 展示当前 Run/Session 的预算、上下文利用率和工具曝光摘要。

## 4. 完整 Team Run

在交互模式输入以下固定任务（整段一行也可）：

```text
/run start 使用 Team 模式把订单履约 CLI 升级为支持 CSV 批量导入、库存原子预留、幂等订单处理、dry-run、JSONL 审计日志、可注入 Clock、错误行报告和完整单元/集成测试；必须读取完整审计规则，分 Worker 实现并通过 Verifier。 --mode team --worktree --verify
```

保存返回的根 `runId`。观察：

```text
/run graph <runId>
/task list <runId>
/run events <runId>
/trace show <runId> --json
```

预期流程：Leader 通过 `StructuredRequest<TeamPlan>` 生成严格计划；任务形成 DAG；Shared-read Worker 仅有 `basic`；可写 Worker 有 `basic + coding`；Verifier 只获得 `basic + verification`。`git/admin` 不会自动出现。每次曝光变化产生 `TOOL_EXPOSURE_CHANGED`。

## 5. 验证 Offload 与 Compression

900 字符工具结果上限会把大审计文件完整写到：

```text
target/ricbot-full-demo/.ricbot/artifacts/<rootRunId>/<runId>/<artifactId>/
```

在事件中查找 `ARTIFACT_OFFLOADED`、`CONTEXT_COMPACTED`。Artifact 结果应包含 SHA-256、字节/字符数、摘要和 `artifact://<runId>/<artifactId>`，而不是静默截断。Worker 应调用 `artifact_grep` 查找 `RULE-0210`，再用 `artifact_read` 分块恢复附近原文。

检查完整性：

```text
/run events <workerRunId>
/run report <workerRunId>
```

验收：报告中 Artifact 大小等于磁盘内容，压缩摘要保留 ArtifactRef；跨 Run URI、任意路径和 `..` 均被拒绝。Artifact v1 不自动清理，以保证 Replay 可解释。

## 6. 审批、暂停与活跃时间

当 Verifier 或 Worker 请求受控 Exec 时，Run 应进入等待并返回 `requestId`：

```text
/run report <runId>
/side-effect list
```

记录报告中的 `activeMillis`，等待至少一分钟，再次执行 `/run report <runId>`。等待审批的时间不得增长活跃时间。审阅命令、Workspace 和风险后：

```text
/approve <requestId>
/run resume <runId>
```

拒绝演练使用 `/reject <requestId>`。同一 SideEffect 的重复 Resume 不应重复执行；`/side-effect show <idempotencyKey>` 应显示稳定的终态和版本。

## 7. Join、Verifier、Diff 与 ChangeSet

Run 完成或等待人工审阅后：

```text
/task list <runId>
/task show <taskId>
/run report <runId>
/workspace
/change create --json
/change diff
/change status
```

人工确认以下验收点：

- CSV 逐行错误不阻断其他合法行；
- 同一订单重复导入不会二次扣库存；
- 库存预留是原子的，并发失败不会留下部分状态；
- `--dry-run` 不写库存和审计文件，但输出确定性计划；
- JSONL 每行可独立解析并含审计规则要求字段；
- Clock 可注入，单测无真实时间依赖；
- 单元与集成测试覆盖重复、缺货、坏行、并发、dry-run 和审计。

通过后：

```text
/change commit-message
/change approve
/change commit --message "feat: complete order fulfillment batch workflow"
```

Ricbot 不会绕过 ChangeSet 和人工审批自动提交。

### Rollback 演练

仅在这个可丢弃仓库中执行：

```text
/change rollback
/change rollback --execute
```

先查看预览再执行。随后用 `/workspace` 和 Git 状态确认回滚边界，不要在 Ricbot 主仓库做该演练。

## 8. Report、Replay、Resume、Fork、Cancel 与查询

```text
/run report <runId>
/run replay <runId>
/run replay <runId> <eventSequence>
/run fork <runId> <safeEventSequence> demo-fork-1
/run resume demo-fork-1
/run cancel demo-fork-1
/task list <runId>
/trace show <runId> --json
/policy show
/policy check verifier exec
/policy check-command verifier "mvn test"
/workspace
/side-effect list
```

`/run report` 应统一展示：Graph 兼容状态、本地/父子 UsageLedger、预算上限/预留/剩余/耗尽原因、Artifact、最新 RuntimeHint、中间件版本、授权/激活工具组、AgentEvent 分类、Task、SideEffect、Approval 与 Verifier 摘要。

v4 Run 可 Resume/Fork。若数据库中存在 v3 Run，它仍可查询、看事件和 Replay，但 Resume/Signal/Fork 必须返回含原 Run ID 与“新建 v4 Run”指引的错误；启动扫描会标记为 `legacy_read_only`，不反复恢复。

## 9. 独立低预算 Run

复制演示配置为 `target/ricbot-full-demo/ricbot.low-budget.json`，只把预算改为：

```json
{
  "max_total_tokens": 1800,
  "max_cost_microusd": 2000,
  "max_active_seconds": 60,
  "max_tool_calls": 1,
  "finalization_tokens": 1024
}
```

启动独立 Session：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c target/ricbot-full-demo/ricbot.low-budget.json \
  --session fulfillment-low-budget \
  --message "检查全部审计规则并修改实现，然后运行所有测试。"
```

预期：普通调用的保守预留无法满足时进入 `BUDGET_EXHAUSTED`；所有非收尾工具隐藏；Runtime 用预留的无工具调用生成总结。若 Provider 失败，返回确定性的预算报告。配置 `max_total_tokens <= finalization_tokens` 应在启动前直接拒绝。

## 10. 真实 Provider Eval Matrix

准备只指向真实模型卡的 Matrix 配置和少量场景，再执行：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval matrix \
  --spec config/examples/eval-matrix.json \
  --scenarios evals/golden.jsonl \
  --config target/ricbot-full-demo/ricbot.demo.config.json \
  --workspace target/ricbot-full-demo \
  --out target/ricbot-full-demo/eval-matrix \
  --limit 3
```

保存 Matrix 报告、Run Report、Trace、事件、Diff、测试结果和费用摘要。确定性 Provider 只用于自动回归与 Replay，不作为本演示主路径。

## 11. 高级可选故障演练

只在可丢弃 Workspace：批准一个持续时间较长、具有副作用的 Exec，在其进入 `EXECUTING` 后强制终止 Ricbot 进程。等待 owner lease 过期，再重启。预期 SideEffect 变为 `UNKNOWN`，不会自动重放：

```text
/side-effect show <idempotencyKey>
/side-effect retry <idempotencyKey>
/approve <retryRequestId>
/run resume <runId>
```

只有绑定到原 Run/Task/Activation/参数摘要的人工授权才能 Retry。无法证明未执行的崩溃预算预留不会自动归还。

## 12. 功能验收矩阵

| 功能 | 命令/动作 | 预期事件 | 验收证据 |
|---|---|---|---|
| v4 启动 | `/run start` | Graph `RUN_STARTED` | report 的 graphId 为 `ricbot-agent-runtime-v4` |
| 累计 Usage | 多轮模型/工具/压缩 | `USAGE_RECORDED` | Token、调用数、activeMillis 单调累计 |
| 分层预算 | 并行 Worker | `BUDGET_RESERVED/SETTLED` | Worker 等分且根总预留不超卖 |
| 预算耗尽 | 低预算 Run | `BUDGET_EXHAUSTED` | 工具隐藏并仅进行一次收尾 |
| RuntimeHint | 每次 MODEL | `RUNTIME_HINT_UPDATED` | 时间按分钟、预算/上下文/任务/Workspace 可见 |
| Middleware | 启动/Resume | middleware state | 稳定 ID、版本一致，未知版本拒绝恢复 |
| Tool group | Worker 角色/动态激活 | `TOOL_EXPOSURE_CHANGED` | `basic` 不可关闭，越权激活拒绝 |
| Offload | 读取大规则 | `ARTIFACT_OFFLOADED` | URI、SHA、大小、磁盘原文一致 |
| Compression | 上下文逼近阈值 | `CONTEXT_COMPACTED` | 摘要保留 ArtifactRef，原文可恢复 |
| 结构化输出 | TeamPlan/摘要 | model/repair usage | strict-tool/JSON/fallback 选择明确，最多修复一次 |
| 类型化消息 | 流式回答 | Start/Delta/End | 最终文本可精确重建，乱序/重复拒绝 |
| HITL | Exec/ChangeSet | Approval + WAITING | 等待时间不计 activeMillis，批准后恢复 |
| SideEffect | 重复 Resume | SideEffect 终态 | 幂等键相同且只执行一次 |
| Team | `/task list` | Task DAG/Join | Worker worktree、依赖和交付可追踪 |
| Verification | `--verify` | verifier report | Maven 测试与验收项通过 |
| Replay/Fork | `/run replay/fork` | replay/fork events | Digest 一致，安全序列可 Fork |
| ChangeSet | `/change ...` | approval/commit | Diff 经人工审阅后才提交 |

## 13. 常见失败

- `MISSING_MODEL_PRICE`：费用限额开启但模型卡缺输入/输出 USD 价格；补卡或移除费用上限，不能用未知费用继续。
- `unknown model` 且工具为空：这是保守降级；添加精确 JSON v1 模型卡，不要恢复 Java 名称猜测。
- Artifact integrity check failed：内容或 manifest 被改动；停止 Run，保留现场，不用截断内容替代。
- Artifact ownership rejected：URI 属于其他 Run/Task；通过合法 Task 交付关系传递，不复制路径。
- middleware version unsupported：代码与 checkpoint 不兼容；查询/Replay 后新建 Run，不静默丢状态。
- approval 一直等待：检查 requestId、绑定 Run 和 SideEffect 状态；等待本身不消耗 activeMillis。
- Exec sandbox 不可用：使用 Config Doctor 建议的受控后端；不要开启静默 fallback。
- v3 Resume/Fork 失败：符合兼容边界；用原目标创建 v4 Run。
- Offload 写失败：检查 `.ricbot/artifacts` 权限和空间；Run 必须停止，不能退回静默截断。

## 14. 保存证据与安全清理

把以下内容复制到演示仓库外的审计目录：最终 `/run report`、`/run events`、Trace export、Task/SideEffect/Approval 摘要、ChangeSet Diff、测试输出、Git log 和实际账单费用。API Key 不得进入证据。

确认目标路径后清理：

```bash
test "$(pwd)" = "$(git rev-parse --show-toplevel)"
test -d target/ricbot-full-demo/.git
rm -rf target/ricbot-full-demo
```

该操作只删除准备脚本创建的可丢弃演示仓库；Artifact v1 没有后台清理，因此由操作者在证据归档后显式删除。
