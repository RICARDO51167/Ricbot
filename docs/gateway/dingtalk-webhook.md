# DingTalk Webhook 入站

Ricbot Gateway 提供 DingTalk 事件入站接口：

```text
POST /webhook/dingtalk
```

当配置了 webhook secret 时，该接口会校验 DingTalk 的 `timestamp + sign`；它接收文本消息，将其归一化到内部 inbound message bus，并忽略 5 分钟内见过的重复事件。

## 配置

在 `channels.dingtalk` 下配置 DingTalk：

```json
{
  "channels": {
    "dingtalk": {
      "enabled": true,
      "app_key": "${DINGTALK_APP_KEY}",
      "app_secret": "${DINGTALK_APP_SECRET}",
      "webhookSecret": "${DINGTALK_WEBHOOK_SECRET}",
      "allow_from": ["*"]
    }
  }
}
```

- `webhookSecret`：入站 webhook 签名密钥。若为空，Ricbot 会回退使用 `app_secret`。若两者都为空，则跳过 DingTalk 签名校验。
- `app_key` / `app_secret`：也供 DingTalk 出站渠道使用。

本地测试时建议将 `api.host` 绑定到 `127.0.0.1`。如果绑定到非 loopback 地址，请配置 `api.bearer_token`，并且不要公网暴露 Console。

## 签名规则

Ricbot 会从 query parameter 或 HTTP header 中读取 DingTalk 签名：

```text
timestamp=<milliseconds>
sign=<base64 hmac>
```

待签名字符串为：

```text
${timestamp}
${secret}
```

也就是 `timestamp + "\n" + secret`，使用同一个 secret 做 HMAC-SHA256 签名，再进行 Base64 编码。

Shell 示例：

```bash
timestamp="$(date +%s)000"
secret="${DINGTALK_WEBHOOK_SECRET:-ricbot-smoke-dingtalk-secret}"
sign="$(printf "%s\n%s" "$timestamp" "$secret" | openssl dgst -sha256 -hmac "$secret" -binary | openssl base64)"
```

## 文本消息示例

```json
{
  "msgId": "ding-msg-1",
  "msgtype": "text",
  "senderStaffId": "user-2",
  "senderNick": "Bob",
  "conversationId": "conv-1",
  "text": {
    "content": "hello dingtalk"
  }
}
```

## 本地 Curl 模拟

本地启动 Ricbot API 后运行：

```bash
timestamp="$(date +%s)000"
secret="${DINGTALK_WEBHOOK_SECRET:-ricbot-smoke-dingtalk-secret}"
sign="$(printf "%s\n%s" "$timestamp" "$secret" | openssl dgst -sha256 -hmac "$secret" -binary | openssl base64)"

curl -sS -X POST "http://127.0.0.1:8000/webhook/dingtalk" \
  -H "Content-Type: application/json" \
  -H "timestamp: $timestamp" \
  -H "sign: $sign" \
  -d '{
    "msgId": "ding-local-1",
    "msgtype": "text",
    "senderStaffId": "local-user",
    "senderNick": "Local User",
    "conversationId": "local-conversation",
    "text": {"content": "hello from local dingtalk smoke"}
  }'
```

如需跨所有已支持平台做可重复本地检查，使用：

```bash
sh scripts/webhook-smoke.sh
```

## 签名排障

- HTTP `401` 且包含 `missing dingtalk signature` 表示已配置 secret，但请求未提供 `timestamp` 或 `sign`。
- HTTP `403` 且包含 `invalid dingtalk signature` 表示 HMAC 输入、secret、Base64 输出或 URL/header 传递方式不匹配。
- 确认服务启动时使用的 `DINGTALK_WEBHOOK_SECRET` 与 curl 命令使用的是同一个值。
- 如果把 `sign` 放在 query string 中，需要进行 URL encode。用 header 传递 `sign` 可以避免 query 编码问题。

## 重复事件

`EventDeduplicator` 使用 `platform + eventId` 作为去重 key，并在内存中保留 5 分钟。DingTalk 依次使用 `msgId`、`messageId`、`eventId`。重复事件会返回 `ok: true`、`delivered: false` 和 `duplicate: true`。

## 当前限制

- 当前支持的入站消息类型是文本。
- 附件、图片、语音和其它非文本消息类型会被报告为 unsupported。
- 入站 smoke 路径未做 DingTalk 平台 SDK 深度集成。
