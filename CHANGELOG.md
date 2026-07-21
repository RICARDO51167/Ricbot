# Changelog

## Runtime Pruning

- Runtime Core 收敛为 CLI、OpenAI-compatible API 与通用 WebSocket 输入，不再内置具体企业通信适配器。
- 删除内置定时任务工具、持久调度器与后台心跳服务；外部调度器通过显式输入或 Resume 接口唤醒任务。
- 删除后台记忆自学习、整文件改写与 Git restore 链路；保留结构化 Memory、显式候选审批和长会话压缩。
- 删除 Experience 自学习闭环、Eval 抽取、自动 Skill 晋升及 Console/命令入口；可复用规则改由人工维护 Skill。

## V5.4 - Provider Capability Override

- 新增顶层 `model_capabilities` 配置，用于覆盖特定模型的 tool calling、streaming、vision、JSON mode、reasoning effort 和 token 窗口能力。
- Provider capability resolver 先执行静态/启发式推断，再合并用户 override，并在 Config Doctor 中展示 `STATIC`、`HEURISTIC`、`USER_OVERRIDE` 或 `MIXED` 来源。
- Config Doctor 增加 capability override 风险提示、非法 token 数 warning 和未命中当前默认模型的低优先级提示。
- 运行时仍只消费最终 ProviderCapability；明确 `false` 的 override 会触发现有 tool/streaming 降级策略。

## V5.2 - Console UI Polish + Demo Flow

- Console 顶部新增 Demo Flow，把 config、team、workspace、trace、experience、eval 和 release-check 映射成演示路径。
- Console 卡片补充 empty/loading/error、数量 badge、last updated 和单卡刷新体验。
- 新增只读 `GET /console/api/release-check`，固定读取 `target/release-check-report.md` 并做敏感文本脱敏。
- 优化现有 Console action 按钮的 loading/disabled 状态，避免演示时重复点击。

## V5.1 - Eval Baseline Management + Regression Report

- 固定 baseline 目录为 `.ricbot/eval-baselines/golden`，release-check 自动识别并纳入 compare。
- 增强 `scripts/eval-baseline.sh`，支持 `create`、`show` 和 `create --force`。
- `target/release-check-report.md` 新增 Baseline、Eval Compare 和 Final Decision 区块，明确 regressions、improvements、unchanged 和 warnings。
- pass -> fail 回归会阻断发布，fail -> pass 作为 improvement 记录；baseline 缺失仍为 warning。

## V5.0 - Eval CI Gate + Release Quality Gate

- 新增本地 `scripts/release-check.sh`，串联全量测试、打包、config doctor、fixed smoke eval 和可选 eval compare。
- 生成 `target/release-check-report.md`，汇总 git 信息、各门禁结果、eval summary、warnings 和 final status。
- 新增 `scripts/eval-baseline.sh`，用于生成本地 compare baseline。
- 门禁不访问真实模型、不访问外网，也不要求真实 API key；config doctor 缺 key 只作为 warning。

## V4.30 - Console Tool/MCP Viewer

- 新增 Console Tool/MCP 只读看板，展示 builtin、MCP、generated 工具概览。
- 展示 MCP server 状态、transport、enabled tools 和 loaded tool count。
- MCP command/env/header 等敏感字段脱敏，不支持从 Console 调用工具或启停 server。

## V4.29 - Console Fixed Smoke Eval

- 新增 `POST /console/api/evals/smoke`，从 Console 触发固定 golden smoke。
- 固定使用 `evals/golden.jsonl`、`target/eval-console-smoke-workspace` 和 deterministic smoke provider。
- Eval artifact 写入 Console workspace 的 `.ricbot/evals`，可直接在 Eval Dashboard 查看。

## V4.28 - Console Workspace Actions

- Console 开放受控 workspace 后处理：Create ChangeSet 和 Discard managed worktree。
- Discard 要求 `confirm=true`，只允许 Ricbot 管理的 active `GIT_WORKTREE`。
- Workspace actions 复用 Console auth、Origin/Referer、rate limit 和 action audit。

## V4.27 - Provider Capability Runtime Fallback

- Provider capability 从诊断展示接入 AgentRunner 运行时策略。
- 明确 `supportsToolCalling=false` 时不暴露 tools，streaming/vision 能力明确不支持时做降级提示。
- `UNKNOWN` 保持原行为，只记录 capability warning。

## V4.25 - Console Action Audit + Security

- Console POST action 统一写入 `console-actions.jsonl` 审计。
- 增加 Origin/Referer CSRF-lite 防护、轻量 rate limit 和敏感字段脱敏。
- Experience/approval 写操作补充幂等和重复提交保护。

## V4.24 - Console Experience/Approval Actions

- Console 开放 experience verify/reject/promote-skill。
- Console 开放 pending approval list 和 approve/reject。
- 写操作复用 API auth，并保持人工确认边界。

## V4.23 - Eval Dashboard

- 新增 Eval Runs Viewer，读取 `.ricbot/evals` artifact。
- Console 展示 run summary、失败 case、manifest 和 report。
- 防止 runId 路径穿越，manifest/report 做脱敏和 HTML escape。

## V4.22 - Web Console

- 新增本地只读 Web Console。
- 展示 Config Doctor、latest trace、team reports、workspace sessions、experience 数据。
- 默认面向本地使用，Console API 不暴露敏感配置。

## V4.21 - Config Doctor + Provider Capability

- 新增 `config doctor`，启动前诊断配置、环境变量、provider 推断和未生效字段。
- 新增 ProviderCapability / ModelCapability 静态能力描述。
- README 明确已生效、部分生效和预留配置字段。

## V4.20 - Trace Viewer / Run Timeline

- 新增 trace viewer 与 run timeline 展示能力。
- 支持查看最近 trace、事件列表和运行摘要。
- 为 Console 和后续调试闭环提供统一 trace 数据源。

## V4.19 - Workspace Lifecycle + ChangeSet Review

- 新增 workspace list/status/diff/discard 生命周期能力。
- 新增 ChangeSet create/status/diff/commit-message/approve 等 review 工作流。
- worktree-backed 任务可以通过 ChangeSet 收口到可审阅变更。

## V4.18 - Worktree-backed Team Execution

- Team task 支持受管 git worktree 隔离执行。
- Worker/verifier 绑定同一工作区，失败后保留 worktree 便于排查。
- Team report 与 workspace diff 形成任务后处理入口。

## V4.17 - Experience to Generated Skill

- Verified experience 支持 promote 为 generated skill。
- 经验治理从候选、验证扩展到可复用技能资产。
- 避免 candidate/rejected experience 污染上下文。

## V4.16 - TeamTaskReport

- 新增 TeamTaskReport，汇总 team task 状态、事件、产物和风险。
- 为 `/team report` 和 Console team 区块提供数据基础。
- 改善多角色任务执行后的人工审阅体验。

## V4.15 - StepAudit Compact Summary

- 新增 StepAudit compact summary，压缩实现步骤和审计记录。
- 为长任务、team execution 和 trace 复盘减少上下文噪音。
- 改善任务执行后的可读摘要和后续决策输入。
