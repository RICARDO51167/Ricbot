# MCP Diagnostics

Ricbot exposes read-only MCP diagnostics for Console and local troubleshooting:

```text
GET /console/api/mcp/diagnostics
```

The endpoint does not start, stop, reload, reconnect, or call MCP tools. It only summarizes configured servers, loaded tools, filters, schema snapshots, and warnings already visible to the runtime.

## Configuration

MCP servers are configured under `tools.mcpServers`:

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

Supported transport names are `stdio`, `sse`, and `streamableHttp`. If `type` is blank, Ricbot infers `stdio` from `command` and infers HTTP transport from `url`.

## Diagnostic Fields

Each server row includes:

- `name`: configured server name.
- `transportType`: effective transport.
- `enabled`: whether the server exists in config.
- `status`: `CONNECTED`, `FAILED`, `DISABLED`, `CONFIGURED`, or `UNKNOWN`.
- `loadedToolCount`: number of MCP tools registered into `ToolRegistry`.
- `registeredToolNames`: wrapped tool names exposed as `mcp_<server>_<tool>`.
- `filteredToolNames`: wrapped tool names skipped by `enabled_tools`.
- `disabledReason`: explanation when the server or its tools are not exposed.
- `lastError`: redacted load error.
- `configWarnings`: configuration and filter warnings.

Each tool row explains:

- whether it was registered into `ToolRegistry`;
- whether `enabled_tools` allows it;
- whether toolsets restricted it;
- whether it is ultimately exposed to the model;
- the reason when it is not exposed.

Ricbot does not currently configure MCP toolsets, so `allowedByToolsets` is `true` and `toolsetsRestricted` is `false`.

## Schema Snapshot

Diagnostics include `schemaSummary` and `schemaHash`.

`schemaSummary` is a sorted summary of currently registered MCP tool schemas. `schemaHash` is a SHA-256 hash of a canonicalized schema summary, so it is stable across calls when the registered MCP tool surface does not change.

## Common Failures

- Command missing: `status=FAILED`, `lastError` usually mentions process start failure.
- Timeout: health or tool calls may report timeout; increase `tool_timeout` for slow servers.
- Tool list empty: server may be connected but `loadedToolCount=0`.
- `enabled_tools` filtering: tools returned by the MCP server but not listed in `enabled_tools` appear in `filteredToolNames`.
- Unmatched `enabled_tools`: diagnostics include a config warning when configured names do not match raw or wrapped tool names.

## Redaction

Diagnostics redact sensitive values in command args, env, URL query strings, and errors when they contain token, secret, password, api key, authorization, bearer, or cookie markers. Non-sensitive environment variables are shown as `[SET]`, not as raw values.

## Current Limits

- Console is read-only for MCP diagnostics.
- Console does not start, stop, reload, or reconnect MCP servers.
- Console does not call MCP tools.
- Diagnostics are local runtime observations, not an online compatibility probe.
