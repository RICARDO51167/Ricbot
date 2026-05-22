package ricbot.integration.api.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.InboundMessages;
import ricbot.domain.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.integration.api.RicbotApiAppContext;
import ricbot.integration.api.RicbotApiServer;
import ricbot.integration.channel.DingTalkChannel;
import ricbot.integration.channel.FeishuChannel;
import ricbot.integration.channel.WecomChannel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChannelWebhookController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final EventDeduplicator DEDUPLICATOR = new EventDeduplicator(300_000L);

    private ChannelWebhookController() {
    }

    public static void register(HttpServer server, RicbotApiAppContext appContext) {
        server.createContext("/webhook/feishu", new WebhookHandler(appContext, "feishu"));
        server.createContext("/webhook/dingtalk", new WebhookHandler(appContext, "dingtalk"));
        server.createContext("/webhook/wecom", new WebhookHandler(appContext, "wecom"));
    }

    public static HttpHandler handler(RicbotApiAppContext appContext, String platform) {
        return new WebhookHandler(appContext, platform);
    }

    private record WebhookHandler(RicbotApiAppContext appContext, String platform) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            String body;
            try {
                body = readBody(exchange);
            } catch (BodyTooLargeException e) {
                RicbotApiServer.writeErrorJson(exchange, 413, "webhook body too large", "request_too_large");
                return;
            }

            Map<String, Object> payload;
            try {
                payload = body.isBlank() ? Map.of() : MAPPER.readValue(body, MAP_TYPE);
            } catch (Exception e) {
                RicbotApiServer.writeErrorJson(exchange, 400, "invalid webhook JSON", "invalid_request_error");
                return;
            }

            try {
                WebhookResult result = switch (platform) {
                    case "feishu" -> handleFeishu(exchange, appContext, payload);
                    case "dingtalk" -> handleDingTalk(exchange, appContext, payload);
                    case "wecom" -> handleWecom(exchange, appContext, payload);
                    default -> throw new WebhookException(404, "unknown webhook platform", "not_found");
                };
                RicbotApiServer.writeJson(exchange, 200, result.body());
            } catch (WebhookException e) {
                RicbotApiServer.writeErrorJson(exchange, e.statusCode(), e.getMessage(), e.type());
            } catch (Exception e) {
                RicbotApiServer.writeJson(exchange, 500, Map.of(
                        "error", Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                "type", "webhook_error"
                        )
                ));
            }
        }
    }

    private static WebhookResult handleFeishu(HttpExchange exchange, RicbotApiAppContext appContext, Map<String, Object> payload) throws Exception {
        FeishuChannel.FeishuConfig config = appContext.getConfig().getChannels().getFeishu();
        if (payload.containsKey("challenge")) {
            verifyFeishuToken(config, payload);
            return new WebhookResult(Map.of("challenge", string(payload.get("challenge"))));
        }
        verifyFeishuToken(config, payload);
        Map<String, Object> header = asMap(payload.get("header"));
        Map<String, Object> event = asMap(payload.get("event"));
        Map<String, Object> message = asMap(event.get("message"));
        Map<String, Object> sender = asMap(event.get("sender"));
        Map<String, Object> senderId = asMap(sender.get("sender_id"));
        String eventId = firstText(header.get("event_id"), message.get("message_id"));
        if (DEDUPLICATOR.seenBefore("feishu", eventId)) {
            return success("feishu", false, true, outboundConfigured(appContext, "feishu"), "duplicate event ignored");
        }
        String messageType = firstText(message.get("message_type"), event.get("message_type"), "text");
        if (!"text".equalsIgnoreCase(messageType)) {
            return success("feishu", false, false, outboundConfigured(appContext, "feishu"), "unsupported message type: " + messageType);
        }
        String userId = firstText(senderId.get("user_id"), senderId.get("open_id"), event.get("open_id"), event.get("user_id"));
        String chatId = firstText(message.get("chat_id"), event.get("chat_id"), userId);
        String content = extractText(message.get("content"));
        Map<String, Object> metadata = baseMetadata(payload);
        metadata.put("feishu_event_id", eventId);
        metadata.put("feishu_event_type", firstText(header.get("event_type"), payload.get("type")));
        metadata.put("feishu_message_id", firstText(message.get("message_id")));
        metadata.put("feishu_message_type", messageType);
        publish(appContext, InboundMessages.of("feishu", userId, chatId, content, List.of(), metadata, "feishu:" + chatId, null));
        return success("feishu", true, false, outboundConfigured(appContext, "feishu"), "message delivered");
    }

    private static WebhookResult handleDingTalk(HttpExchange exchange, RicbotApiAppContext appContext, Map<String, Object> payload) throws Exception {
        DingTalkChannel.DingTalkConfig config = appContext.getConfig().getChannels().getDingtalk();
        verifyDingTalkSignature(exchange, config);
        String eventId = firstText(payload.get("msgId"), payload.get("messageId"), payload.get("eventId"));
        if (DEDUPLICATOR.seenBefore("dingtalk", eventId)) {
            return success("dingtalk", false, true, outboundConfigured(appContext, "dingtalk"), "duplicate event ignored");
        }
        String msgType = firstText(payload.get("msgtype"), payload.get("msgType"), "text");
        if (!"text".equalsIgnoreCase(msgType)) {
            return success("dingtalk", false, false, outboundConfigured(appContext, "dingtalk"), "unsupported message type: " + msgType);
        }
        String senderId = firstText(payload.get("senderStaffId"), payload.get("senderId"), payload.get("senderCorpId"));
        String chatId = firstText(payload.get("conversationId"), payload.get("chatId"), senderId);
        Map<String, Object> text = asMap(payload.get("text"));
        String content = firstText(text.get("content"), payload.get("content"));
        Map<String, Object> metadata = baseMetadata(payload);
        metadata.put("dingtalk_msg_id", eventId);
        metadata.put("dingtalk_msg_type", msgType);
        metadata.put("sender_nick", firstText(payload.get("senderNick")));
        publish(appContext, InboundMessages.of("dingtalk", senderId, chatId, content, List.of(), metadata, "dingtalk:" + chatId, null));
        return success("dingtalk", true, false, outboundConfigured(appContext, "dingtalk"), "message delivered");
    }

    private static WebhookResult handleWecom(HttpExchange exchange, RicbotApiAppContext appContext, Map<String, Object> payload) throws Exception {
        WecomChannel.WecomConfig config = appContext.getConfig().getChannels().getWecom();
        verifyWecomToken(exchange, config, payload);
        if (payload.containsKey("echostr")) {
            return new WebhookResult(Map.of("echostr", string(payload.get("echostr"))));
        }
        String eventId = firstText(payload.get("msgid"), payload.get("message_id"), payload.get("MsgId"));
        if (DEDUPLICATOR.seenBefore("wecom", eventId)) {
            return success("wecom", false, true, outboundConfigured(appContext, "wecom"), "duplicate event ignored");
        }
        String msgType = firstText(payload.get("msgtype"), payload.get("msgType"), payload.get("MsgType"), "text");
        if (!"text".equalsIgnoreCase(msgType)) {
            return success("wecom", false, false, outboundConfigured(appContext, "wecom"), "unsupported message type: " + msgType);
        }
        String senderId = firstText(payload.get("from_userid"), payload.get("FromUserName"), payload.get("externalUserId"));
        String chatId = firstText(payload.get("roomid"), payload.get("chat_id"), payload.get("roomId"), senderId);
        String content = firstText(payload.get("content"), payload.get("Content"));
        Map<String, Object> metadata = baseMetadata(payload);
        metadata.put("wecom_msg_id", eventId);
        metadata.put("wecom_msg_type", msgType);
        publish(appContext, InboundMessages.of("wecom", senderId, chatId, content, List.of(), metadata, "wecom:" + chatId, null));
        return success("wecom", true, false, outboundConfigured(appContext, "wecom"), "message delivered");
    }

    private static void publish(RicbotApiAppContext appContext, InboundMessage message) throws Exception {
        MessageBus bus = appContext.getAgentLoop() != null ? appContext.getAgentLoop().getBus() : null;
        if (bus == null) {
            throw new WebhookException(500, "message bus unavailable", "webhook_error");
        }
        bus.publishInbound(message);
    }

    private static void verifyFeishuToken(FeishuChannel.FeishuConfig config, Map<String, Object> payload) {
        String expected = config != null ? config.getWebhookToken() : "";
        if (expected != null && !expected.isBlank() && !expected.equals(string(payload.get("token")))) {
            throw new WebhookException(403, "invalid feishu webhook token", "authentication_error");
        }
    }

    private static void verifyDingTalkSignature(HttpExchange exchange, DingTalkChannel.DingTalkConfig config) {
        String secret = config != null && config.getWebhookSecret() != null && !config.getWebhookSecret().isBlank()
                ? config.getWebhookSecret()
                : config != null ? config.getAppSecret() : "";
        if (secret == null || secret.isBlank()) {
            return;
        }
        String timestamp = firstText(query(exchange, "timestamp"), exchange.getRequestHeaders().getFirst("timestamp"));
        String sign = firstText(query(exchange, "sign"), exchange.getRequestHeaders().getFirst("sign"));
        if (timestamp.isBlank() || sign.isBlank()) {
            throw new WebhookException(401, "missing dingtalk signature", "authentication_error");
        }
        String expected = dingtalkSign(timestamp, secret);
        String decoded = urlDecode(sign);
        if (!constantEquals(expected, decoded) && !constantEquals(expected, sign)) {
            throw new WebhookException(403, "invalid dingtalk signature", "authentication_error");
        }
    }

    private static void verifyWecomToken(HttpExchange exchange, WecomChannel.WecomConfig config, Map<String, Object> payload) {
        String expected = config != null ? config.getToken() : "";
        if (expected == null || expected.isBlank()) {
            return;
        }
        String token = firstText(query(exchange, "token"), payload.get("token"));
        if (!expected.equals(token)) {
            throw new WebhookException(403, "invalid wecom webhook token", "authentication_error");
        }
    }

    static String dingtalkSign(String timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("dingtalk signature failed");
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new BodyTooLargeException();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> baseMetadata(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raw", redact(payload));
        return out;
    }

    private static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() != null ? String.valueOf(entry.getKey()) : "";
                if (isSensitiveKey(key)) {
                    out.put(key, "[REDACTED]");
                } else {
                    out.put(key, redact(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ChannelWebhookController::redact).toList();
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("signature")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("cookie");
    }

    private static WebhookResult success(String platform, boolean delivered, boolean duplicate, boolean outboundConfigured, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("platform", platform);
        body.put("delivered", delivered);
        body.put("duplicate", duplicate);
        body.put("outboundConfigured", outboundConfigured);
        body.put("message", message);
        return new WebhookResult(body);
    }

    private static boolean outboundConfigured(RicbotApiAppContext appContext, String platform) {
        Config.ChannelsConfig channels = appContext.getConfig().getChannels();
        return channels != null && channels.isEnabled(platform);
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        return Map.of();
    }

    private static String extractText(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return firstText(map.get("text"), map.get("content"));
        }
        String value = string(raw);
        if (value.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(value, MAP_TYPE);
            return firstText(parsed.get("text"), parsed.get("content"));
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String text = string(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String query(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawQuery() : "";
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            String name = idx >= 0 ? part.substring(0, idx) : part;
            if (key.equals(urlDecode(name))) {
                return idx >= 0 ? urlDecode(part.substring(idx + 1)) : "";
            }
        }
        return "";
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value != null ? value : "", StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value != null ? value : "";
        }
    }

    private static boolean constantEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                (left != null ? left : "").getBytes(StandardCharsets.UTF_8),
                (right != null ? right : "").getBytes(StandardCharsets.UTF_8)
        );
    }

    private record WebhookResult(Map<String, Object> body) {
    }

    private static final class BodyTooLargeException extends IOException {
    }

    private static final class WebhookException extends RuntimeException {
        private final int statusCode;
        private final String type;

        private WebhookException(int statusCode, String message, String type) {
            super(message);
            this.statusCode = statusCode;
            this.type = type;
        }

        private int statusCode() {
            return statusCode;
        }

        private String type() {
            return type;
        }
    }
}
