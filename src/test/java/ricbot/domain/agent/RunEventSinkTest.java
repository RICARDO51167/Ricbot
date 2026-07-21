package ricbot.domain.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunEventSinkTest {

    @Test
    void diagnosticFailureDoesNotInvalidateDurableEvent() {
        List<RunEvent> durableEvents = new ArrayList<>();
        RunEventSink sink = RunEventSink.durableWithDiagnostics(
                durableEvents::add,
                event -> {
                    throw new IllegalStateException("otlp unavailable");
                }
        );
        RunEvent event = event();

        assertDoesNotThrow(() -> sink.append(event));
        assertEquals(List.of(event), durableEvents);
    }

    @Test
    void durableFailureStillStopsDiagnosticFanout() {
        AtomicInteger diagnosticCalls = new AtomicInteger();
        RunEventSink sink = RunEventSink.durableWithDiagnostics(
                event -> {
                    throw new RunJournalException("journal unavailable", new IllegalStateException("disk failure"));
                },
                event -> diagnosticCalls.incrementAndGet()
        );

        assertThrows(RunJournalException.class, () -> sink.append(event()));
        assertEquals(0, diagnosticCalls.get());
    }

    private static RunEvent event() {
        return RunEvent.create(
                1,
                "run-1",
                "session-1",
                0,
                RunEventType.RUN_STARTED,
                RunStatus.CREATED,
                null,
                Map.of()
        );
    }
}
