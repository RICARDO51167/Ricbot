package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunResumeServiceTest {
    @Test
    void reconstructsAndPersistsAnExecutableHistoricalFork(@TempDir Path workspace) {
        SessionManager sessions = new SessionManager(workspace);
        FileRunCheckpointStore checkpoints = new FileRunCheckpointStore(workspace);
        FileRunJournalStore journal = new FileRunJournalStore(workspace);
        Session source = sessions.getOrCreate("source")
                .setMessages(new ArrayList<>(List.of(
                        Map.of("role", "user", "content", "baseline"),
                        Map.of("role", "assistant", "content", "later persisted message")
                )))
                .setMetadata(Map.of("tenant", "t1"));
        sessions.save(source);
        journal.append(event(1, RunEventType.RUN_STARTED, RunStatus.CREATED));
        journal.append(event(2, RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING));
        journal.append(event(3, RunEventType.MODEL_RESPONSE_RECEIVED, RunStatus.WAITING_TOOL));
        RunCheckpoint checkpoint = new RunCheckpoint(
                1, "cp-3", "parent", "parent", "source", 3, 1, 1,
                RunCheckpointPhase.MODEL_RESPONSE_RECEIVED,
                AgentNodeState.initial(),
                List.of(Map.of("role", "assistant", "content", "historical response")),
                Map.of("role", "assistant", "content", "historical response"),
                List.of(), List.of(), Map.of(), "", Instant.now());
        checkpoints.save(checkpoint);

        RunResumeService service = new RunResumeService(sessions, checkpoints, journal);
        ExecutableRunFork fork = service.forkAt("source", "parent", 3, "child-session", "child-run");

        assertEquals(List.of("baseline", "historical response"), fork.source().messages().stream()
                .map(message -> String.valueOf(message.get("content"))).toList());
        assertEquals(fork.source().messages(), sessions.find("child-session").orElseThrow().getMessages());
        assertEquals("t1", fork.childSession().getMetadata().get("tenant"));
        assertEquals("parent", fork.childSession().getMetadata().get("fork_parent_run"));
        AgentRunSpec spec = fork.applyTo(new AgentRunSpec().setRunEventSink(journal));
        assertEquals("child-run", spec.getInitialRunState().runId());
        assertEquals(1, spec.getInitialRunState().lastSequence());
        assertEquals(2, spec.getCheckpointMessageOffset());
    }

    @Test
    void emitterContinuesFromForkSequence() {
        List<RunEvent> events = new ArrayList<>();
        RunState forkState = RunState.from(RunEvent.create(
                1, "child", "session", 1, RunEventType.RUN_FORKED, RunStatus.CREATED, null, Map.of()));
        RunEventEmitter emitter = new RunEventEmitter(events::add, forkState);

        RunEvent event = emitter.emit(2, RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null, Map.of());

        assertEquals(2, event.sequence());
        assertEquals("child", event.runId());
    }

    private static RunEvent event(long sequence, RunEventType type, RunStatus status) {
        return RunEvent.create(sequence, "parent", "source", 1, type, status, null, Map.of());
    }
}
