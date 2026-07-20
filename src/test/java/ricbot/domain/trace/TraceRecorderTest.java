package ricbot.domain.trace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceRecorderTest {

    @Test
    void recordRunEvent_recordsEvent() {
        List<Map<String, Object>> events = new ArrayList<>();
        TraceRecorder recorder = TraceRecorder.forRunEvents(events);

        Map<String, Object> event = recorder.recordRunEvent("run_start", Map.of("session_id", "s1"));

        assertEquals(1, events.size());
        assertSame(event, events.get(0));
        assertEquals("run_start", event.get("type"));
        assertEquals("s1", event.get("session_id"));
        assertNotNull(event.get("at"));
    }

    @Test
    void recordToolEvent_recordsToolCall() {
        List<Map<String, Object>> events = new ArrayList<>();
        TraceRecorder recorder = TraceRecorder.forRunEvents(events);

        Map<String, Object> event = recorder.recordToolEvent("call_1", Map.of("name", "read_file"));

        assertEquals(1, events.size());
        assertEquals("tool_call", event.get("type"));
        assertEquals("call_1", event.get("tool_id"));
        assertEquals("read_file", event.get("name"));
    }

    @Test
    void recordApprovalEvent_recordsApproval() {
        List<Map<String, Object>> events = new ArrayList<>();
        TraceRecorder recorder = TraceRecorder.forRunEvents(events);

        Map<String, Object> event = recorder.recordApprovalEvent("approval_1", Map.of("status", "approved"));

        assertEquals(1, events.size());
        assertEquals("approval_event", event.get("type"));
        assertEquals("approval_1", event.get("approval_id"));
        assertEquals("approved", event.get("status"));
    }
}
