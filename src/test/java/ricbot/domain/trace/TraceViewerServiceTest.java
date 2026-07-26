package ricbot.domain.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TraceViewerServiceTest {
    @Test
    void findsRuntimeTraceByTaskReference(@TempDir Path workspace) {
        TraceStore store = new TraceStore(workspace);
        String traceId = store.traceIdForSession("session");
        store.append(new TraceEvent(traceId, null, "", "session", "", "", "", TraceEventType.WORKER_FINISHED,
                "worker", "done", Map.of("taskId", "task-1", "runId", "run-1"), null, null));
        TraceTimeline timeline = new TraceViewerService(workspace).show("task-1");
        assertEquals("task-1", timeline.taskId());
        assertEquals(1, timeline.events().size());
    }
}
