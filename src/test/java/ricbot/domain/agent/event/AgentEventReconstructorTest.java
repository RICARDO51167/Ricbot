package ricbot.domain.agent.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventReconstructorTest {
    @Test void rebuildsOnlyACompleteOrderedMessage() {
        List<AgentEvent> events = List.of(new AgentEvent.MessageStart(meta(1), "m"),
                new AgentEvent.MessageDelta(meta(2), "m", "hello "),
                new AgentEvent.MessageDelta(meta(3), "m", "world"),
                new AgentEvent.MessageEnd(meta(4), "m", "stop"));
        assertEquals("hello world", new AgentEventReconstructor().reconstruct(events, "m"));
        assertThrows(IllegalArgumentException.class, () -> new AgentEventReconstructor().reconstruct(
                List.of(events.get(0), events.get(2), events.get(3)), "m"));
    }
    private static AgentEvent.EventMeta meta(long sequence) {
        return new AgentEvent.EventMeta("e" + sequence, sequence, "run", "session", "task",
                "", "run", Instant.EPOCH);
    }
}
