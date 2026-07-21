# MCP 诊断

Ricbot 为 Console 和本地排障提供只读 MCP 诊断接口：

```text
GET /console/api/mcp/diagnostics
```

该接口不会启动、停止、reload、重连或调用 MCP 工具。它只汇总当前运行时已可见的已配置 server、已加载工具、过滤规则、schema snapshot 和 warning。

## 配置

MCP server 配置在 `tools.mcpServers` 下：

```json
{
  "tools": {
    "mcpServers": {
      "demo": {
        "type": "stdio",
        "command": "node",
        "args": ["server.js"],
        "env": {
          "API_TOKEN": "${DEMO_MCP_TOKEN}"
        },
        "enabled_tools": ["echo"],
        "tool_timeout": 30
      }
    }
  }
}
```

支持的 transport 名称包括 `stdio` 和 `streamableHttp`。如果 `type` 为空，Ricbot 会根据 `command` 推断为 `stdio`，并根据 `url` 推断为 Streamable HTTP。旧 `sse` 配置会返回明确迁移提示，不会被静默忽略。

## 诊断字段

每个 server 行包含：

- `name`：配置中的 server 名称。
- `transportType`：实际生效的 transport。
- `enabled`：该 server 是否存在于配置中。
- `status`：`CONNECTED`、`FAILED`、`DISABLED`、`CONFIGURED` 或 `UNKNOWN`。
- `loadedToolCount`：注册到 `ToolRegistry` 的 MCP 工具数量。
- `registeredToolNames`：包装后暴露为 `mcp_<server>_<tool>` 的工具名。
- `filteredToolNames`：被 `enabled_tools` 跳过的包装后工具名。
- `disabledReason`：server 或其工具未暴露时的原因。
- `lastError`：脱敏后的加载错误。
- `configWarnings`：配置和过滤 warning。

每个 tool 行说明：

- 它是否已注册到 `ToolRegistry`；
- `enabled_tools` 是否允许它；
- toolsets 是否限制了它；
- 它最终是否暴露给模型；
- 未暴露时的原因。

Ricbot 当前未配置 MCP toolsets，因此 `allowedByToolsets` 为 `true`，`toolsetsRestricted` 为 `false`。

## Schema Snapshot

诊断结果包含 `schemaSummary` 和 `schemaHash`。

`schemaSummary` 是当前已注册 MCP 工具 schema 的排序摘要。`schemaHash` 是规范化 schema 摘要的 SHA-256 hash；只要注册的 MCP 工具面不变，它在多次调用之间就是稳定的。

## 常见故障

- 命令缺失：`status=FAILED`，`lastError` 通常会提到进程启动失败。
- 超时：health 或工具调用可能报告 timeout；慢 server 可增大 `tool_timeout`。
- 工具列表为空：server 可能已连接，但 `loadedToolCount=0`。
- `enabled_tools` 过滤：MCP server 返回但未列在 `enabled_tools` 中的工具会出现在 `filteredToolNames`。
- `enabled_tools` 未命中：配置名无法匹配原始或包装后工具名时，诊断会包含 config warning。

## 脱敏

当 command args、env、URL query string 或错误信息包含 token、secret、password、api key、authorization、bearer、cookie 等标记时，诊断会进行脱敏。非敏感环境变量显示为 `[SET]`，不会显示原始值。

## 当前限制

- Console 中的 MCP 诊断是只读的。
- Console 不会启动、停止、reload 或重连 MCP server。
- Console 不会调用 MCP 工具。
- 诊断结果是本地运行时观察，不是在线兼容性探测。
