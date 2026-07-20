package ricbot.domain.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunStateTest {

    @Test
    void rejectsSequenceGapsAndIllegalTransitions() {
        RunState state = RunState.from(event(1, RunEventType.RUN_STARTED, RunStatus.CREATED));

        assertThrows(IllegalArgumentException.class, () -> state.apply(
                event(3, RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING)
        ));
        assertThrows(IllegalStateException.class, () -> state.apply(
                event(2, RunEventType.TOOL_CALL_STARTED, RunStatus.TOOL_RUNNING)
        ));
    }

    @Test
    void terminalStateCannotRestart() {
        RunState completed = RunState.from(event(1, RunEventType.RUN_STARTED, RunStatus.CREATED))
                .apply(event(2, RunEventType.RUN_FINISHED, RunStatus.COMPLETED));

        assertThrows(IllegalStateException.class, () -> completed.apply(
                event(3, RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING)
        ));
    }

    private static RunEvent event(long sequence, RunEventType type, RunStatus status) {
        return RunEvent.create(sequence, "run-1", "cli:direct", 1, type, status, null, Map.of());
    }
}
