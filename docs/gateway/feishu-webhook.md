# Feishu Webhook 入站

Ricbot Gateway 提供 Feishu 事件入站接口：

```text
POST /webhook/feishu
```

该接口接收 Feishu URL verification 回调和文本消息事件；当配置了 verification token 时会进行校验；文本消息会被归一化到内部 inbound message bus；5 分钟内见过的重复事件会被忽略。

## 配置

在 `channels.feishu` 下配置 Feishu：

```json
{
  "channels": {
    "feishu": {
      "enabled": true,
      "app_id": "${FEISHU_APP_ID}",
      "app_secret": "${FEISHU_APP_SECRET}",
      "webhookToken": "${FEISHU_WEBHOOK_TOKEN}",
      "encryptKey": "${FEISHU_ENCRYPT_KEY}",
      "allow_from": ["*"]
    }
  }
}
```

- `webhookToken`：Feishu event subscription verification token。配置后，入站 payload 的 `token` 必须与其一致。
- `encryptKey`：为加密事件回调预留。当前入站链路不会解密 Feishu 加密事件。
- `app_id` / `app_secret`：供 Feishu 出站渠道使用；本地 webhook 解析 smoke test 不需要它们。

本地测试时建议将 `api.host` 绑定到 `127.0.0.1`。如果绑定到非 loopback 地址，请配置 `api.bearer_token`，并且不要公网暴露 Console。

## Challenge 回调

Feishu 在事件订阅设置阶段会发送 URL verification 回调：

```json
{
  "type": "url_verification",
  "token": "${FEISHU_WEBHOOK_TOKEN}",
  "challenge": "challenge-code"
}
```

Ricbot 会返回：

```json
{
  "challenge": "challenge-code"
}
```

## 文本消息示例

```json
{
  "token": "${FEISHU_WEBHOOK_TOKEN}",
  "header": {
    "event_id": "feishu-evt-1",
    "event_type": "im.message.receive_v1"
  },
  "event": {
    "sender": {
      "sender_id": {
        "user_id": "user-1"
      }
    },
    "message": {
      "message_id": "msg-1",
      "chat_id": "chat-1",
      "message_type": "text",
      "content": "{\"text\":\"hello feishu\"}"
    }
  }
}
```

## 本地 Curl 模拟

本地启动 Ricbot API 后运行：

```bash
curl -sS -X POST "http://127.0.0.1:8000/webhook/feishu" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "'"${FEISHU_WEBHOOK_TOKEN:-ricbot-smoke-feishu-token}"'",
    "header": {"event_id": "feishu-local-1", "event_type": "im.message.receive_v1"},
    "event": {
      "sender": {"sender_id": {"user_id": "local-user"}},
      "message": {
        "message_id": "feishu-local-msg-1",
        "chat_id": "local-chat",
        "message_type": "text",
        "content": "{\"text\":\"hello from local feishu smoke\"}"
      }
    }
  }'
```

如需跨所有已支持平台做可重复本地检查，使用：

```bash
sh scripts/webhook-smoke.sh
```

## Token 排障

- HTTP `403` 且包含 `invalid feishu webhook token` 表示已配置 `channels.feishu.webhookToken`，但入站 JSON 的 `token` 不匹配。
- 启动服务前，请确认该值已从环境变量正确解析。
- 如果本地 smoke 请求刻意不做 token 强制校验，可在本地配置中留空 `webhookToken`。

## 重复事件

`EventDeduplicator` 使用 `platform + eventId` 作为去重 key，并在内存中保留 5 分钟。Feishu 优先使用 `header.event_id`，再回退到 `message.message_id`。重复事件会返回 `ok: true`、`delivered: false` 和 `duplicate: true`。

## 当前限制

- 尚未实现 Feishu 加密事件解密。
- 附件、图片、语音和其它非文本消息类型尚未归一化为入站文本消息。
- 当前支持的入站消息类型是文本。
