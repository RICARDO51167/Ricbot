package ricbot.domain.message;

import java.util.HashMap;
import java.util.Map;

public final class OutboundMessages {

    private OutboundMessages() {
    }

    public static OutboundMessage of(String channel, String chatId, String content) {
        return of(channel, chatId, content, new HashMap<>());
    }

    public static OutboundMessage of(String channel, String chatId, String content, Map<String, Object> metadata) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(channel);
        out.setChatId(chatId);
        out.setContent(content);
        out.setMetadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        return out;
    }

    public static OutboundMessage replyTo(InboundMessage msg, String content) {
        return replyTo(msg, content, copyMetadata(msg));
    }

    public static OutboundMessage replyTo(InboundMessage msg, String content, Map<String, Object> metadata) {
        return of(msg != null ? msg.getChannel() : null, msg != null ? msg.getChatId() : null, content, metadata);
    }

    public static Map<String, Object> copyMetadata(InboundMessage msg) {
        if (msg == null || msg.getMetadata() == null) {
            return new HashMap<>();
        }
        return new HashMap<>(msg.getMetadata());
    }
}
