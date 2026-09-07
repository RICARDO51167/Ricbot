package ricbot.domain.runtime;

import java.util.Objects;

public record ChannelWrite(String channel, Operation operation, Object value) {
    public enum Operation { SET, APPEND, MERGE, REMOVE }
    public ChannelWrite {
        channel = channel != null ? channel.trim() : "";
        if (channel.isBlank()) throw new IllegalArgumentException("channel is required");
        operation = Objects.requireNonNullElse(operation, Operation.SET);
        if (operation != Operation.REMOVE) Objects.requireNonNull(value, "value");
    }
    public static ChannelWrite set(String channel, Object value) { return new ChannelWrite(channel, Operation.SET, value); }
}
