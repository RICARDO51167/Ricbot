package ricbot.domain.runtime;

import java.time.Instant;
import java.util.Map;

public record TimelineEvent(long sequence, String eventId, String runId, String type,
                            Instant occurredAt, Map<String, Object> detail) {
    public TimelineEvent { detail = Map.copyOf(detail != null ? detail : Map.of()); }
}
