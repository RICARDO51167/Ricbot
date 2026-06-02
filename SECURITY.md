# 安全策略

## 支持的使用方式

Ricbot 设计为本地或私有部署的 Agent Runtime。已启用的工具和渠道都应视为具备特权能力。

## 密钥

- 配置文件中优先使用 `${RICBOT_API_KEY}` 这类环境变量占位符，不要直接写入真实密钥。
- Ricbot 在 POSIX 文件系统上写入配置文件时，会尝试设置仅所有者可读写权限（`0600`）。
- 不要提交 `workspace/`、`.ricbot/`、日志、token 或生成的运行时状态。

## 网络暴露

- 将 OpenAI-compatible API 绑定到非 loopback 地址时，必须配置 `api.bearer_token`。
- 聊天渠道的 `allow_from` 应保持收敛。只有在可信本地测试中才使用 `["*"]`。
- Web 和 exec 工具包含 SSRF/路径检查，但启用后仍会授予较宽的本地能力。

## 漏洞报告

如果发现漏洞，请私下报告给项目维护者，不要在公开 issue 中披露可利用细节。
