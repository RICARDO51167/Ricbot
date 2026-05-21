package ricbot.integration.channel.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 命令事件记录类。
 */
public record CommandEvent(
        String channel,
        String chatId,
        String senderId,
        String senderName,
        String command,
        String args,
        Map<String, Object> metadata,
        String sessionKeyOverride,
        String eventId,
        LocalDateTime timestamp
) implements ChannelEvent {

    @Override
    public ChannelEventType type() {
        return ChannelEventType.COMMAND;
    }

    @Override
    public String text() {
        String c = command != null ? command.trim() : "";
        String a = args != null ? args.trim() : "";
        
        if (c.isBlank()) {
            return "";
        }
        
        if (a.isBlank()) {
            return "/" + c;
        }
        
        return "/" + c + " " + a;
    }
}

