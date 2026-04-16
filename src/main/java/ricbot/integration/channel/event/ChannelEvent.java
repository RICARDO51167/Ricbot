package ricbot.integration.channel.event;

import ricbot.domain.message.InboundMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道事件接口
 */
public interface ChannelEvent {

    ChannelEventType type();

    String channel();

    String chatId();

    String senderId();

    default String senderName() {
        return null;
    }

    default String eventId() {
        return null;
    }

    default String sessionKeyOverride() {
        return null;
    }

    default LocalDateTime timestamp() {
        return null;
    }

    default Map<String, Object> metadata() {
        return Map.of();
    }

    default String text() {
        return "";
    }

    default List<String> media() {
        return List.of();
    }

    default InboundMessage toInboundMessage() {
        InboundMessage msg = new InboundMessage();
        msg.setChannel(channel());
        msg.setChatId(chatId());
        msg.setSenderId(senderId());
        msg.setContent(text());
        msg.setSessionKeyOverride(sessionKeyOverride());

        if (timestamp() != null) {
            msg.setTimestamp(timestamp());
        }

        List<String> m = media();
        msg.setMedia(m != null ? new ArrayList<>(m) : new ArrayList<>());

        Map<String, Object> meta = metadata() != null ? new HashMap<>(metadata()) : new HashMap<>();
        meta.putIfAbsent("_event_type", type().name());
        if (eventId() != null && !eventId().isBlank()) {
            meta.putIfAbsent("_event_id", eventId());
        }
        if (senderName() != null && !senderName().isBlank()) {
            meta.putIfAbsent("sender_name", senderName());
        }
        msg.setMetadata(meta);
        return msg;
    }
}

