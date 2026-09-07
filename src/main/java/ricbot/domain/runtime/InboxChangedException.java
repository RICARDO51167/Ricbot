package ricbot.domain.runtime;

import java.util.List;

/** Signals that an event reached the durable Inbox after a phase result was prepared. */
public final class InboxChangedException extends RuntimeException {
    private final List<String> eventIds;

    public InboxChangedException(List<String> eventIds) {
        super("runtime inbox changed before commit: " + eventIds);
        this.eventIds = List.copyOf(eventIds != null ? eventIds : List.of());
    }

    public List<String> eventIds() { return eventIds; }
}
