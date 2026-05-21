package ricbot.integration.channel.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 传入消息事件记录。
 */
public record IncomingMessageEvent(
        String channel,
        String chatId,
        String senderId,
        String senderName,
        String content,
        List<String> media,
        Map<String, Object> metadata,
        String sessionKeyOverride,
        String eventId,
        LocalDateTime timestamp
) implements ChannelEvent {

    @Override
    public ChannelEventType type() {
        return ChannelEventType.INCOMING_MESSAGE;
    }

    @Override
    public String text() {
        return content != null ? content : "";
    }
}

