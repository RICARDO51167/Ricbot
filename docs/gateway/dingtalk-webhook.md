# DingTalk Webhook Ingress

Ricbot Gateway exposes DingTalk event ingress at:

```text
POST /webhook/dingtalk
```

The endpoint verifies DingTalk `timestamp + sign` when a webhook secret is configured, accepts text messages, normalizes them into the internal inbound message bus, and ignores duplicate events seen within 5 minutes.

## Configuration

Configure DingTalk under `channels.dingtalk`:

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

- `webhookSecret`: inbound webhook signing secret. If blank, Ricbot falls back to `app_secret`. If both are blank, DingTalk signature verification is skipped.
- `app_key` / `app_secret`: also used by the DingTalk outbound channel.

Keep `api.host` bound to `127.0.0.1` for local testing. If binding to a non-loopback host, configure `api.bearer_token` and do not expose the Console publicly.

## Signature Rule

Ricbot expects the DingTalk signature in either query parameters or HTTP headers:

```text
timestamp=<milliseconds>
sign=<base64 hmac>
```

The string to sign is:

```text
${timestamp}
${secret}
```

That is `timestamp + "\n" + secret`, signed with HMAC-SHA256 using the same secret, then Base64 encoded.

Shell example:

```bash
timestamp="$(date +%s)000"
secret="${DINGTALK_WEBHOOK_SECRET:-ricbot-smoke-dingtalk-secret}"
sign="$(printf "%s\n%s" "$timestamp" "$secret" | openssl dgst -sha256 -hmac "$secret" -binary | openssl base64)"
```

## Text Message Example

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

## Local Curl Simulation

Start Ricbot API locally, then run:

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

For a repeatable local check across all supported platforms, use:

```bash
sh scripts/webhook-smoke.sh
```

## Signature Troubleshooting

- HTTP `401` with `missing dingtalk signature` means a secret is configured but `timestamp` or `sign` was not provided.
- HTTP `403` with `invalid dingtalk signature` means the HMAC input, secret, Base64 output, or URL/header transport does not match.
- Ensure the server was started with the same `DINGTALK_WEBHOOK_SECRET` used by the curl command.
- If you put `sign` in the query string, URL-encode it. Passing `sign` as a header avoids query encoding issues.

## Duplicate Events

`EventDeduplicator` keys duplicates by `platform + eventId` and keeps entries for 5 minutes in memory. DingTalk uses `msgId`, then `messageId`, then `eventId`. A duplicate returns `ok: true`, `delivered: false`, and `duplicate: true`.

## Current Limits

- Current supported inbound message type is text.
- Attachments, images, voice, and other non-text message types are reported as unsupported.
- No DingTalk platform SDK deep integration is performed by the inbound smoke path.
