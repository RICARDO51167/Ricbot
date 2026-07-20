package ricbot.integration.api.webhook;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public class EventDeduplicator {
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public EventDeduplicator(long ttlMillis) {
        this(ttlMillis, System::currentTimeMillis);
    }

    EventDeduplicator(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : 300_000L;
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    public boolean seenBefore(String platform, String eventId) {
        String id = eventId != null ? eventId.trim() : "";
        if (id.isBlank()) {
            return false;
        }
        long now = clock.getAsLong();
        cleanup(now);
        String key = (platform != null ? platform : "") + ":" + id;
        Long previous = seen.putIfAbsent(key, now);
        return previous != null && now - previous <= ttlMillis;
    }

    private void cleanup(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > ttlMillis) {
                it.remove();
            }
        }
    }
}
