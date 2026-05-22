# WeCom Webhook Ingress

Ricbot Gateway exposes WeCom event ingress at:

```text
POST /webhook/wecom
```

The endpoint verifies the configured token when present, accepts text messages, normalizes them into the internal inbound message bus, and ignores duplicate events seen within 5 minutes.

## Configuration

Configure WeCom under `channels.wecom`:

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

- `token`: inbound webhook token. If this value is configured, incoming query parameter `token` or JSON field `token` must match it.
- `secret`: used by the WeCom outbound channel; it is not used for current plaintext webhook token verification.

Keep `api.host` bound to `127.0.0.1` for local testing. If binding to a non-loopback host, configure `api.bearer_token` and do not expose the Console publicly.

## Token Verification

Ricbot checks token in this order:

```text
POST /webhook/wecom?token=<token>
```

Then, if the query parameter is absent, it checks JSON field:

```json
{
  "token": "${WECOM_WEBHOOK_TOKEN}"
}
```

If `channels.wecom.token` is blank, token verification is skipped.

## Text Message Example

```json
{
  "msgid": "wecom-msg-1",
  "msgtype": "text",
  "from_userid": "external-1",
  "roomid": "room-1",
  "content": "hello wecom"
}
```

## Local Curl Simulation

Start Ricbot API locally, then run:

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

For a repeatable local check across all supported platforms, use:

```bash
sh scripts/webhook-smoke.sh
```

## Encrypted Callback Reservation

WeCom encrypted callbacks usually include `msg_signature`, `timestamp`, `nonce`, and encrypted payload content. Ricbot currently reserves the encrypted callback path but does not decrypt `msg_signature` callbacks. Use plaintext local simulation for current smoke testing.

## Duplicate Events

`EventDeduplicator` keys duplicates by `platform + eventId` and keeps entries for 5 minutes in memory. WeCom uses `msgid`, then `message_id`, then `MsgId`. A duplicate returns `ok: true`, `delivered: false`, and `duplicate: true`.

## Current Limits

- `msg_signature` encrypted callback decryption is not implemented.
- Current supported inbound message type is text.
- Attachments, images, voice, and other non-text message types are not normalized as inbound text messages.
