package ricbot.domain.agent.event;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict reconstruction: gaps, duplicate sequence numbers and incomplete messages are rejected. */
public final class AgentEventReconstructor {
    public String reconstruct(List<AgentEvent> events, String messageId) {
        StringBuilder content = new StringBuilder();
        Set<Long> sequences = new HashSet<>();
        long previous = 0;
        boolean started = false;
        boolean ended = false;
        for (AgentEvent event : events != null ? events : List.<AgentEvent>of()) {
            if (!sequences.add(event.meta().sequence()) || event.meta().sequence() != previous + 1) {
                throw new IllegalArgumentException("agent event sequence has a gap, duplicate, or is out of order");
            }
            previous = event.meta().sequence();
            if (event instanceof AgentEvent.MessageStart start && messageId.equals(start.messageId())) {
                if (started) throw new IllegalArgumentException("duplicate message_start");
                started = true;
            } else if (event instanceof AgentEvent.MessageDelta delta && messageId.equals(delta.messageId())) {
                if (!started || ended) throw new IllegalArgumentException("message_delta outside message boundary");
                content.append(delta.delta() != null ? delta.delta() : "");
            } else if (event instanceof AgentEvent.MessageEnd end && messageId.equals(end.messageId())) {
                if (!started || ended) throw new IllegalArgumentException("invalid message_end");
                ended = true;
            }
        }
        if (!started || !ended) throw new IllegalArgumentException("incomplete message event stream");
        return content.toString();
    }
}
