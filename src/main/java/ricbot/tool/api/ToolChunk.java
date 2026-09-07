package ricbot.tool.api;

import java.time.Instant;

public record ToolChunk(long sequence, Kind kind, Object value, Instant timestamp) {
    public enum Kind { STDOUT, STDERR, PROGRESS, STRUCTURED }
    public ToolChunk { if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative"); timestamp = timestamp != null ? timestamp : Instant.now(); }
}
