# Feishu Webhook Ingress

Ricbot Gateway exposes Feishu event ingress at:

```text
POST /webhook/feishu
```

The endpoint accepts Feishu URL verification callbacks and text message events, verifies the configured verification token when present, normalizes text messages into the internal inbound message bus, and ignores duplicate events seen within 5 minutes.

## Configuration

Configure Feishu under `channels.feishu`:

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

- `webhookToken`: Feishu event subscription verification token. If this value is configured, incoming payload `token` must match it.
- `encryptKey`: reserved for encrypted event callbacks. Current ingress does not decrypt encrypted Feishu events.
- `app_id` / `app_secret`: used by the Feishu outbound channel, not required for local webhook parsing smoke tests.

Keep `api.host` bound to `127.0.0.1` for local testing. If binding to a non-loopback host, configure `api.bearer_token` and do not expose the Console publicly.

## Challenge Callback

Feishu sends a URL verification callback during event subscription setup:

```json
{
  "type": "url_verification",
  "token": "${FEISHU_WEBHOOK_TOKEN}",
  "challenge": "challenge-code"
}
```

Ricbot replies with:

```json
{
  "challenge": "challenge-code"
}
```

## Text Message Example

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

## Local Curl Simulation

Start Ricbot API locally, then run:

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

For a repeatable local check across all supported platforms, use:

```bash
sh scripts/webhook-smoke.sh
```

## Token Troubleshooting

- HTTP `403` with `invalid feishu webhook token` means `channels.feishu.webhookToken` is configured and the incoming JSON `token` does not match.
- Make sure the value is resolved from the environment before starting the server.
- If you intentionally want local smoke requests without token enforcement, leave `webhookToken` blank in the local config.

## Duplicate Events

`EventDeduplicator` keys duplicates by `platform + eventId` and keeps entries for 5 minutes in memory. Feishu uses `header.event_id` first, then falls back to `message.message_id`. A duplicate returns `ok: true`, `delivered: false`, and `duplicate: true`.

## Current Limits

- Encrypted Feishu event decryption is not implemented.
- Attachments, images, voice, and other non-text message types are not normalized as inbound text messages.
- Current supported inbound message type is text.
