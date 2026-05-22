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

本地 smoke 脚本：

```bash
sh scripts/smoke.sh
```

该脚本只运行快速测试、打包、config doctor 和固定 smoke eval，不会访问真实模型。

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
- Feishu / DingTalk / WeCom 文本入站已支持，复杂加密回调、附件、语音仍是部分支持
- Provider capability 是静态/启发式；只有明确 `false` 的能力才触发运行时降级，`UNKNOWN` 不阻断
- OpenAI-compatible 聚合网关和本地模型的 capability 可能需要用户通过 provider/model 配置显式修正

## 文档地图

- [CHANGELOG.md](CHANGELOG.md)：V4.15-V4.30 能力演进
- [docs/demo/end-to-end-coding-agent.md](docs/demo/end-to-end-coding-agent.md)：端到端演示
- [docs/security/console-safety.md](docs/security/console-safety.md)：Console 安全边界
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
