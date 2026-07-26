package ricbot.domain.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceRendererTest {

    @Test
    void rendersTraceSummary(@TempDir Path workspace) {
        TraceStore store = new TraceStore(workspace);
        store.append(new TraceEvent(
                "trace_render",
                null,
                "",
                "session",
                "",
                "changeset_1",
                "approval_1",
                TraceEventType.CHANGESET_COMMIT_REQUESTED,
                "change",
                "commit requested",
                Map.of("status", "PENDING"),
                null,
                null
        ));

        String rendered = new TraceRenderer().renderSummary(store.summarize("trace_render"), store.loadEvents("trace_render"));

        assertTrue(rendered.contains("trace trace_render"), rendered);
        assertTrue(rendered.contains("path: sqlite:.ricbot/runtime.db#traces/trace_render"), rendered);
        assertTrue(rendered.contains("eventTypes: CHANGESET_COMMIT_REQUESTED"), rendered);
        assertTrue(rendered.contains("approvals: approval_1"), rendered);
        assertTrue(rendered.contains("changeSets: changeset_1"), rendered);
    }

    @Test
    void rendersEventsTimeline() {
        TraceEvent event = TraceEvent.of("trace_events", "session", TraceEventType.TEAM_EVENT, "team", "started", Map.of());

        String rendered = new TraceRenderer().renderEvents(List.of(event));

        assertTrue(rendered.contains("trace events"), rendered);
        assertTrue(rendered.contains("TEAM_EVENT"), rendered);
        assertTrue(rendered.contains("eventId=" + event.eventId()), rendered);
        assertTrue(rendered.contains("message=started"), rendered);
    }
}
