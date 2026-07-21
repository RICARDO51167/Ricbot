# Console Safety

Ricbot Console 是本地运维与演示面板。默认建议只监听 `127.0.0.1`，不要暴露到公网。

## 只读能力

以下 Console API 只读，不触发模型调用、工具调用、MCP reload 或配置修改：

- `GET /console`
- `GET /console/api/health`
- `GET /console/api/config-doctor`
- `GET /console/api/traces`
- `GET /console/api/team-reports`
- `GET /console/api/workspaces`
- `GET /console/api/approvals`
- `GET /console/api/actions`
- `GET /console/api/evals`
- `GET /console/api/evals/<run-id>`
- `GET /console/api/tools`
- `GET /console/api/mcp`

Tools/MCP 看板只读取当前 `ToolRegistry`、MCP server config 摘要和 `MCPLoader` 状态，不调用工具，不启动或停止 MCP server。

## 写操作

当前只开放低风险、人工确认型写操作：

- `POST /console/api/approvals/<id>/approve`
- `POST /console/api/approvals/<id>/reject`
- `POST /console/api/workspaces/<id>/change-create`
- `POST /console/api/workspaces/<id>/discard`
- `POST /console/api/evals/smoke`

Workspace actions 只允许 Ricbot 管理的 active `GIT_WORKTREE`。`discard` 必须请求体包含 `confirm=true`。

Eval smoke 固定运行 `evals/golden.jsonl`、`target/eval-console-smoke-workspace` 和 deterministic smoke provider，不接受自定义 scenario/workspace/provider。

## 鉴权

如果配置了 `api.bearer_token`，Console POST 必须带：

```text
Authorization: Bearer <token>
```

未鉴权 POST 会返回 401，并写入 action audit。

## Origin / Referer

Console POST 会检查 `Origin` / `Referer`：

- 如果 header 存在，必须来自本机 Console origin
- 非本机 origin 会返回 403
- 缺失 header 时允许 CLI/curl 场景，但响应和审计会保留 warning

该规则不影响 `/v1/chat/completions` 等 OpenAI-compatible API。

## Rate Limit

Console POST 有轻量内存限流：

- 维度：`remoteAddress + action`
- 默认：10 秒内最多 20 次
- 超限返回 429，并写入 audit

## Action Audit

所有 Console POST 会追加审计到：

```text
workspace/.ricbot/console-actions.jsonl
```

审计记录包含：

- action
- targetType / targetId
- result
- operator
- remoteAddress
- userAgent
- timestamp
- message
- warnings
- requestId

审计写入失败不会阻断主操作，但会返回 warning。

## 敏感信息脱敏

Console API、action result 和 audit record 会对常见敏感字段脱敏：

- `api_key`
- `token`
- `secret`
- `password`
- `authorization`
- `bearer`
- `cookie`
- `set-cookie`

MCP 看板不会输出真实 env 值；非敏感 env 只显示 `[SET]`，敏感 env 显示 `[REDACTED]`。

## 不支持的高风险操作

Console 当前不支持：

- 任意工具调用
- 任意 eval / eval compare / eval replay
- team run
- shell exec
- MCP reload/reconnect/start/stop
- workspace 任意路径 discard
- git merge
- git commit
- provider 配置修改

这些操作仍应通过 CLI、受控 workflow 或人工审阅执行。

## 部署建议

- 默认绑定 `127.0.0.1`
- 不要公网暴露 Console
- 如果必须绑定 `0.0.0.0`，必须配置强 bearer token，并放在可信网络后
- 不要把生产密钥写进 approval args、eval artifact 或 MCP env 的可见字段
