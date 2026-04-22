package ricbot.domain.message;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InboundMessages {

    private InboundMessages() {
    }

    public static InboundMessage of(String channel, String senderId, String chatId, String content) {
        return of(channel, senderId, chatId, content, List.of(), Map.of(), null, null);
    }

    public static InboundMessage of(
            String channel,
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKeyOverride,
            LocalDateTime timestamp
    ) {
        InboundMessage msg = new InboundMessage();
        msg.setChannel(channel);
        msg.setSenderId(senderId);
        msg.setChatId(chatId);
        msg.setContent(content);
        msg.setMedia(media != null ? new ArrayList<>(media) : new ArrayList<>());
        msg.setMetadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        msg.setSessionKeyOverride(sessionKeyOverride);
        if (timestamp != null) {
            msg.setTimestamp(timestamp);
        }
        return msg;
    }
}
