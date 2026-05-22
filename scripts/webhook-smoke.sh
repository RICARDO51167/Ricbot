#!/usr/bin/env sh
set -u

BASE_URL="${RICBOT_BASE_URL:-http://127.0.0.1:8000}"
FEISHU_TOKEN="${FEISHU_WEBHOOK_TOKEN:-ricbot-smoke-feishu-token}"
DINGTALK_SECRET="${DINGTALK_WEBHOOK_SECRET:-ricbot-smoke-dingtalk-secret}"
WECOM_TOKEN="${WECOM_WEBHOOK_TOKEN:-ricbot-smoke-wecom-token}"

TMP_DIR="${TMPDIR:-/tmp}"
REQUEST_BODY="$TMP_DIR/ricbot-webhook-smoke-body.$$"
RESPONSE_BODY="$TMP_DIR/ricbot-webhook-smoke-response.$$"

cleanup() {
  rm -f "$REQUEST_BODY" "$RESPONSE_BODY"
}
trap cleanup EXIT INT TERM

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing required command: $1" >&2
    exit 1
  fi
}

json_request() {
  label="$1"
  url="$2"
  shift 2
  status="$(curl -sS -o "$RESPONSE_BODY" -w "%{http_code}" --connect-timeout 2 --max-time 10 \
    -X POST "$url" \
    -H "Content-Type: application/json" \
    "$@" \
    --data-binary "@$REQUEST_BODY" 2>"$RESPONSE_BODY.err")"
  curl_exit=$?

  echo "== $label =="
  if [ "$curl_exit" -ne 0 ]; then
    echo "HTTP status: 000"
    echo "curl error:"
    cat "$RESPONSE_BODY.err"
    echo
    echo "Server does not appear reachable at $BASE_URL. Start Ricbot serve first; this script does not start it."
    rm -f "$RESPONSE_BODY.err"
    exit 1
  fi

  echo "HTTP status: $status"
  echo "response:"
  cat "$RESPONSE_BODY"
  echo
  rm -f "$RESPONSE_BODY.err"
}

dingtalk_sign() {
  timestamp="$1"
  secret="$2"
  printf "%s\n%s" "$timestamp" "$secret" | openssl dgst -sha256 -hmac "$secret" -binary | openssl base64
}

require_command curl
require_command openssl

echo "Ricbot webhook smoke target: $BASE_URL"
echo

cat >"$REQUEST_BODY" <<EOF
{
  "type": "url_verification",
  "token": "$FEISHU_TOKEN",
  "challenge": "ricbot-smoke-challenge"
}
EOF
json_request "Feishu challenge" "$BASE_URL/webhook/feishu"

cat >"$REQUEST_BODY" <<EOF
{
  "token": "$FEISHU_TOKEN",
  "header": {
    "event_id": "feishu-smoke-1",
    "event_type": "im.message.receive_v1"
  },
  "event": {
    "sender": {
      "sender_id": {
        "user_id": "smoke-user"
      }
    },
    "message": {
      "message_id": "feishu-smoke-msg-1",
      "chat_id": "smoke-chat",
      "message_type": "text",
      "content": "{\"text\":\"hello from feishu smoke\"}"
    }
  }
}
EOF
json_request "Feishu text" "$BASE_URL/webhook/feishu"
json_request "Feishu duplicate" "$BASE_URL/webhook/feishu"

timestamp="$(date +%s)000"
sign="$(dingtalk_sign "$timestamp" "$DINGTALK_SECRET")"
cat >"$REQUEST_BODY" <<EOF
{
  "msgId": "dingtalk-smoke-1",
  "msgtype": "text",
  "senderStaffId": "smoke-user",
  "senderNick": "Smoke User",
  "conversationId": "smoke-conversation",
  "text": {
    "content": "hello from dingtalk smoke"
  }
}
EOF
json_request "DingTalk text" "$BASE_URL/webhook/dingtalk" -H "timestamp: $timestamp" -H "sign: $sign"
json_request "DingTalk duplicate" "$BASE_URL/webhook/dingtalk" -H "timestamp: $timestamp" -H "sign: $sign"

cat >"$REQUEST_BODY" <<EOF
{
  "msgid": "wecom-smoke-1",
  "msgtype": "text",
  "from_userid": "smoke-user",
  "roomid": "smoke-room",
  "content": "hello from wecom smoke"
}
EOF
json_request "WeCom text" "$BASE_URL/webhook/wecom?token=$WECOM_TOKEN"
json_request "WeCom duplicate" "$BASE_URL/webhook/wecom?token=$WECOM_TOKEN"

echo "webhook smoke completed"
