# WeCom Webhook 入站

Ricbot Gateway 提供 WeCom 事件入站接口：

```text
POST /webhook/wecom
```

当配置了 token 时，该接口会进行校验；它接收文本消息，将其归一化到内部 inbound message bus，并忽略 5 分钟内见过的重复事件。

## 配置

在 `channels.wecom` 下配置 WeCom：

```json
{
  "channels": {
    "wecom": {
      "enabled": true,
      "bot_id": "${WECOM_BOT_ID}",
      "secret": "${WECOM_SECRET}",
      "token": "${WECOM_WEBHOOK_TOKEN}",
      "allow_from": ["*"]
    }
  }
}
```

- `token`：入站 webhook token。配置后，入站 query parameter `token` 或 JSON 字段 `token` 必须与其一致。
- `secret`：供 WeCom 出站渠道使用；当前明文 webhook token 校验不使用它。

本地测试时建议将 `api.host` 绑定到 `127.0.0.1`。如果绑定到非 loopback 地址，请配置 `api.bearer_token`，并且不要公网暴露 Console。

## Token 校验

Ricbot 按以下顺序检查 token：

```text
POST /webhook/wecom?token=<token>
```

如果 query parameter 不存在，再检查 JSON 字段：

```json
{
  "token": "${WECOM_WEBHOOK_TOKEN}"
}
```

如果 `channels.wecom.token` 为空，则跳过 token 校验。

## 文本消息示例

```json
{
  "msgid": "wecom-msg-1",
  "msgtype": "text",
  "from_userid": "external-1",
  "roomid": "room-1",
  "content": "hello wecom"
}
```

## 本地 Curl 模拟

本地启动 Ricbot API 后运行：

```bash
curl -sS -X POST "http://127.0.0.1:8000/webhook/wecom?token=${WECOM_WEBHOOK_TOKEN:-ricbot-smoke-wecom-token}" \
  -H "Content-Type: application/json" \
  -d '{
    "msgid": "wecom-local-1",
    "msgtype": "text",
    "from_userid": "local-user",
    "roomid": "local-room",
    "content": "hello from local wecom smoke"
  }'
```

如需跨所有已支持平台做可重复本地检查，使用：

```bash
sh scripts/webhook-smoke.sh
```

## 加密回调预留

WeCom 加密回调通常包含 `msg_signature`、`timestamp`、`nonce` 和加密 payload 内容。Ricbot 当前预留了加密回调路径，但不会解密 `msg_signature` 回调。当前 smoke test 请使用明文本地模拟。

## 重复事件

`EventDeduplicator` 使用 `platform + eventId` 作为去重 key，并在内存中保留 5 分钟。WeCom 依次使用 `msgid`、`message_id`、`MsgId`。重复事件会返回 `ok: true`、`delivered: false` 和 `duplicate: true`。

## 当前限制

- 尚未实现 `msg_signature` 加密回调解密。
- 当前支持的入站消息类型是文本。
- 附件、图片、语音和其它非文本消息类型尚未归一化为入站文本消息。
