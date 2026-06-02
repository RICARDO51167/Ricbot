# Ricbot 发布检查清单

## 必跑门禁

运行发布门禁：

```bash
sh scripts/release-check.sh
```

预期结果：

- `mvn test`: PASS
- `package`: PASS
- `eval smoke`: PASS
- `eval compare`：存在 baseline 时应为 PASS
- `config doctor`：优先为 OK/WARNING；只有在明确由演示环境缺少本地 API key 导致时，ERROR 才可接受

查看当前 baseline：

```bash
sh scripts/eval-baseline.sh show
```

显式运行 config doctor：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json
```

## Console Smoke

启动 API 与 Console：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

打开：

```text
http://127.0.0.1:8000/console
```

检查：

- Demo Flow 可见
- Config Doctor 卡片可加载
- Release Check 卡片能读取 `target/release-check-report.md`
- Trace / Team / Workspace 卡片能展示数据或有用的空状态
- Eval Runs 卡片可加载
- Tools / MCP 卡片可加载
- MCP diagnostics endpoint 有响应：

```text
GET /console/api/mcp/diagnostics
```

## Webhook Smoke

在 `serve` 已运行的情况下：

```bash
sh scripts/webhook-smoke.sh
```

预期结果：

- Feishu challenge 返回 200
- Feishu/DingTalk/WeCom 文本请求返回结构化 JSON
- 重复请求返回 `duplicate: true`
- 不访问真实平台或外部网络

## 需要确认的当前限制

- Console 以本地使用为优先；未配置 `api.bearer_token` 时不要公网暴露。
- Console MCP Hub 只读；不支持 reload/reconnect/start/stop，也不支持工具调用。
- Console UI 中唯一开放的 eval 操作是固定 smoke eval。
- Team worktree 变更仍需要人工 diff/ChangeSet review。
- Feishu/WeCom 加密 webhook 回调解密尚未实现。
- 附件、图片、语音 webhook payload 尚未归一化为文本。
- Provider capability override 是用户声明，不是在线探测。
- 缺少本地 API key 可能导致 config doctor 报 `ERROR`；deterministic smoke eval 和 release-check 仍可通过。

## Tag 命名

推荐 tag 名称：

```text
v5.6-demo-release
v5.6.0-demo
ricbot-v5.6-demo
```

公开 GitHub release 优先使用：

```text
v5.6.0
```

## 最终人工检查

- README 链接可正常跳转。
- `docs/architecture/ricbot-architecture.md` 反映当前模块边界。
- `docs/demo/demo-script.md` 可在 5-8 分钟内照着演示。
- `docs/interview/project-pitch.md` 包含简洁版本和追问回答。
- `docs/resume/ricbot-bullets.md` 包含中文和英文简历 bullet。
- `target/release-check-report.md` 不包含密钥。
- `git status --short` 只显示预期发布变更。
