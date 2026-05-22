# Ricbot

Ricbot 是一个 Java 17 Agent Runtime：把 CLI、OpenAI-compatible API、多渠道消息、工具调用、团队执行、工作区变更、经验沉淀、评测和本地 Web Console 串成可演示、可回放、可治理的工程化闭环。

完整历史 README 已迁移到 [docs/reference/full-readme-v4.md](docs/reference/full-readme-v4.md)。本 README 聚焦快速运行、演示路线和发布前安全边界。

## 核心闭环

```text
/team run <task> --worktree --verify
  -> /team report <taskId>
  -> /workspace diff <taskId>
  -> /change create <taskId>
  -> /trace show <taskId>
  -> /experience verify <id>
  -> /experience promote-skill <id>
  -> /console
```

这个闭环覆盖：

- team task 在受管 worktree 中执行与验证
- workspace diff 和 ChangeSet review 收口变更
- trace viewer 复盘运行过程
- verified experience 转成 generated skill
- Console 汇总 config、trace、team、workspace、experience、approval、eval、tools 和 MCP 状态

端到端演示脚本见 [docs/demo/end-to-end-coding-agent.md](docs/demo/end-to-end-coding-agent.md)。

## Quickstart

环境要求：

- JDK 17+
- Maven wrapper 使用仓库内 `./mvnw`
- 可选：配置真实模型 API key；smoke eval 不需要真实模型或外网

快速验证：

```bash
sh ./mvnw -q test
sh ./mvnw -q -DskipTests package
```

启动前诊断配置：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json
```

运行固定 smoke eval：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-smoke-workspace \
  --out target/eval-smoke-artifacts
```

启动 API 和 Console：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

打开：

```text
http://127.0.0.1:8000/console
```

## 5 分钟演示路径

先生成发布门禁报告，供 Console 展示：

```bash
sh scripts/release-check.sh
```

再做一次配置诊断：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json
```

启动服务并打开 Console：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

```text
http://127.0.0.1:8000/console
```

演示时从顶部 Demo Flow 讲起：Config Doctor 对应启动前诊断，Team Reports / Workspaces / Trace 对应 team run 后处理，Experience 对应经验治理，Eval Runs 和 Release Check 对应确定性评测门禁，Tools / MCP 展示运行时工具面。没有真实 key 时 config doctor 可能是 `WARNING` 或 `ERROR`，但 fixed smoke eval 和 release-check 的本地 smoke 部分不会访问真实模型。

本地 smoke 脚本：

```bash
sh scripts/smoke.sh
```

该脚本只运行快速测试、打包、config doctor 和固定 smoke eval，不会访问真实模型。

Gateway webhook 本地 smoke：

```bash
sh scripts/webhook-smoke.sh
```

该脚本默认请求 `http://127.0.0.1:8000`，可通过 `RICBOT_BASE_URL` 覆盖；它只用 curl 模拟 Feishu、DingTalk、WeCom 文本入站和重复事件，不访问真实平台，也不会启动服务。

发布门禁：

```bash
sh scripts/release-check.sh
```

`release-check.sh` 会顺序执行全量测试、打包、config doctor、固定 smoke eval，并在存在 baseline 时执行 eval compare。baseline 固定读取 `.ricbot/eval-baselines/golden`，报告写入 `target/release-check-report.md`。

结果规则：

- `PASS`：测试、打包、fixed smoke eval 通过，且 compare 无 pass -> fail 回归
- `WARNING`：核心门禁通过，但 config doctor 报告缺本地 key，或没有 eval baseline 可比较
- `FAIL`：测试/打包/smoke eval 失败，或 eval compare 发现 pass -> fail 回归；fail -> pass 会作为 improvement 记录，不阻断

config doctor 缺少真实 API key 只作为诊断 warning，不会阻断 release-check；fixed smoke eval 使用确定性 provider，不需要真实 key。CI 中可直接调用 `sh scripts/release-check.sh`，再上传 `target/release-check-report.md`。

baseline 管理：

```bash
sh scripts/eval-baseline.sh create
sh scripts/eval-baseline.sh show
sh scripts/eval-baseline.sh create --force
```

`create` 使用固定 `evals/golden.jsonl` 和 deterministic smoke provider，不访问真实模型、外网或真实 API key；baseline 已存在时默认拒绝覆盖，需要显式 `--force`。

## Provider Capability Override

Ricbot 默认通过静态/启发式规则推断模型能力。对于 OpenAI-compatible 中转、私有模型、代理网关或同名模型能力不一致的部署，可以用顶层 `model_capabilities` 声明覆盖：

```json
{
  "agents": {
    "defaults": {
      "model": "qwen-plus"
    }
  },
  "model_capabilities": {
    "qwen-plus": {
      "supportsToolCalling": true,
      "supportsStreaming": true,
      "supportsVision": false,
      "supportsJsonMode": true,
      "supportsReasoningEffort": false,
      "contextWindowTokens": 131072,
      "maxOutputTokens": 8192,
      "apiMode": "openai-compatible"
    }
  }
}
```

本轮选择顶层 `model_capabilities`，因为当前配置模型已经以 `agents.defaults.model` 为核心入口，`ProviderCapabilityResolver` 集中负责 provider/model 能力合并；顶层结构实现面小，也避免把各 provider 配置改成新的嵌套弱类型。需要区分 provider 时，可以把 key 写成 `provider/model`，例如 `dashscope/qwen-plus`。

合并规则：先读取静态/启发式 capability，再应用用户 override；只覆盖显式配置的字段，未配置字段保持原推断。布尔能力支持 `true`、`false` 和 `"UNKNOWN"` 三态；`contextWindowTokens`、`maxOutputTokens` 必须是正数，否则会被忽略并由 config doctor 给出 warning。

Config Doctor 会在 provider capability 中展示 `source`：`STATIC`、`HEURISTIC`、`USER_OVERRIDE` 或 `MIXED`。Override 是用户声明，不是在线探测；错误声明可能导致运行时主动降级，或把不支持的能力暴露给 provider 后触发调用错误。完整示例见 [config/examples/model-capabilities.json](config/examples/model-capabilities.json)。

## Console 能力总览

Console 默认跟随 `serve` 启动，建议只绑定 `127.0.0.1`。

只读看板：

- Config Doctor：配置文件、workspace、provider 推断、API key 是否解析、未生效字段和 suggested fixes
- Trace Viewer：latest trace / timeline / run events
- Team Reports：team session 和 task report
- Workspaces：workspace session、受管 worktree 状态
- Experience：candidate / verified / generated skill 状态
- Eval Runs：读取 `workspace/.ricbot/evals` 下已有 artifact
- Tools / MCP：当前 ToolRegistry、`mcp_*` 工具、MCP server 状态
- Console Actions：最近 Console 写操作审计

人工确认型写操作：

- Experience：`verify`、`reject`、`promote-skill`
- Approval：`approve`、`reject`
- Workspace：受管 active `GIT_WORKTREE` 的 `change-create`、`discard`
- Eval：固定 `Run Smoke Eval`

Console safety 细节见 [docs/security/console-safety.md](docs/security/console-safety.md)。

## 常用命令

CLI 单次调用：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json \
  -m "帮我总结这个仓库"
```

交互模式：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json
```

团队执行：

```text
/team run 修复某个小问题 --worktree --verify
/team report <taskId>
/workspace diff <taskId>
/change create <taskId>
/trace show <taskId>
```

经验治理：

```text
/experience list
/experience verify <id>
/experience promote-skill <id>
```

评测：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval lint --scenarios evals/golden.jsonl
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke --scenarios evals/golden.jsonl
```

## 安全边界

- Console POST 复用 bearer auth；如果配置了 `api.bearer_token`，未鉴权请求会被拒绝
- Console POST 做 Origin/Referer 检查；非本机 Console origin 会被拒绝
- Console POST 有轻量内存 rate limit
- Console 写操作会审计到 `workspace/.ricbot/console-actions.jsonl`
- Console API 和 audit 会对 api_key/token/secret/password/authorization/bearer/cookie 等敏感字段脱敏
- File/exec/web 工具仍受 workspace 限制、SSRF 防护、审批和风险策略约束
- 不要把 Console 暴露到公网；如果绑定 `0.0.0.0`，必须配置 bearer token

## 当前限制

- Console 不支持任意 eval；只支持固定 golden smoke eval
- Console 不支持 team run、eval learn、eval compare、eval replay
- Console 不支持 git merge 或 git commit
- Console MCP Hub 当前只读，不支持 reload/reconnect/启停 server，也不能调用工具
- Feishu / DingTalk / WeCom 文本入站已支持；附件、图片、语音和 Feishu/WeCom 加密回调解密当前不支持
- Provider capability 默认是静态/启发式，也支持 `model_capabilities` 用户覆盖；只有最终结果明确为 `false` 的能力才触发运行时降级，`UNKNOWN` 不阻断
- OpenAI-compatible 聚合网关和本地模型的 capability 可能需要用户通过 provider/model 配置显式修正

## 文档地图

- [CHANGELOG.md](CHANGELOG.md)：V4.15-V5.0 能力演进
- [docs/demo/end-to-end-coding-agent.md](docs/demo/end-to-end-coding-agent.md)：端到端演示
- [docs/security/console-safety.md](docs/security/console-safety.md)：Console 安全边界
- [docs/gateway/feishu-webhook.md](docs/gateway/feishu-webhook.md)：Feishu webhook 入站配置、示例和排查
- [docs/gateway/dingtalk-webhook.md](docs/gateway/dingtalk-webhook.md)：DingTalk webhook 签名、示例和排查
- [docs/gateway/wecom-webhook.md](docs/gateway/wecom-webhook.md)：WeCom webhook token、示例和限制
- [docs/demo/self-improving-agent-loop.md](docs/demo/self-improving-agent-loop.md)：自改进闭环演示
- [docs/reference/full-readme-v4.md](docs/reference/full-readme-v4.md)：迁移前完整 README
- [examples/context_engineering_flow.md](examples/context_engineering_flow.md)
- [examples/approval_and_diffreview.md](examples/approval_and_diffreview.md)
- [examples/experience_learning_flow.md](examples/experience_learning_flow.md)
- [examples/eval_learning_flow.md](examples/eval_learning_flow.md)

## License / Contributing

当前仓库未声明正式开源 License。用于公开发布前，请先补充明确 License。

贡献建议：

- 先跑 `sh scripts/smoke.sh`
- 涉及 Console 写操作时补充 audit/auth/origin/rate-limit 测试
- 涉及工具或文件系统时补充安全边界测试
- 涉及 README 的长篇说明优先放入 `docs/`
