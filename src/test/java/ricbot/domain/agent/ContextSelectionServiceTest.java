package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.session.Session;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextSelectionServiceTest {
    @Test
    void selectsRelevantRecentHistory(@TempDir Path workspace) {
        ContextSelectionService service = new ContextSelectionService(new MemoryStore(workspace), new ToolTraceSummarizer());
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "我喜欢蓝色主题"), Map.of("role", "assistant", "content", "记住了"),
                Map.of("role", "user", "content", "审批流程需要继续完善"), Map.of("role", "assistant", "content", "我会处理审批"),
                Map.of("role", "user", "content", "最近消息"), Map.of("role", "assistant", "content", "最近回复"));
        var result = service.select(new ContextSelectionService.SessionPreparedInputs(null, null, List.of()),
                messages, "继续审批", 4);
        assertTrue(result.history().toString().contains("审批流程"));
        assertFalse(result.history().toString().contains("蓝色主题"));
    }

    @Test
    void recordsContextProjectionTrace(@TempDir Path workspace) {
        TraceStore traces = new TraceStore(workspace);
        ContextSelectionService service = new ContextSelectionService(new MemoryStore(workspace),
                new ToolTraceSummarizer(), 32_000, traces);
        service.select(new ContextSelectionService.SessionPreparedInputs("session", null,
                TaskState.fromSession(new Session("session")), List.of()), List.of(), "inspect runtime", 6);
        assertTrue(traces.loadEvents(traces.traceIdForSession("session")).stream()
                .anyMatch(event -> event.type() == TraceEventType.CONTEXT_BUILT));
    }
}
