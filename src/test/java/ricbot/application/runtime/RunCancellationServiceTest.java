package ricbot.application.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.runtime.*;
import ricbot.infra.runtime.SqliteDurableRuntimeStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCancellationServiceTest {
    @Test void sessionCancellationIsDurableBeforeAnyLocalInterruption(@TempDir Path temp) {
        Path database = temp.resolve("runtime.db");
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T01:00:00Z"), ZoneOffset.UTC);
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(database);
             LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, waitingExecutor(),
                     null, clock, "cancel-owner", Duration.ofSeconds(30))) {
            runtime.start(spec("cancel-session-a", "session-a"));
            runtime.start(spec("cancel-session-b", "session-b"));
            RunCancellationService cancellations = new RunCancellationService(runtime, runtime, clock);

            List<RunView> cancelled = cancellations.cancelSession("session-a", "manual_stop");

            assertEquals(1, cancelled.size());
            assertEquals(RunStatus.CANCELLED, cancelled.get(0).state().status());
            assertTrue(cancelled.get(0).state().cancelRequested());
            assertEquals(RunStatus.WAITING, runtime.get("cancel-session-b").orElseThrow().state().status());
        }
        try (SqliteDurableRuntimeStore reopened = new SqliteDurableRuntimeStore(database)) {
            RunState state = reopened.get("cancel-session-a").orElseThrow().state();
            assertEquals(RunStatus.CANCELLED, state.status());
            assertTrue(state.cancelRequested());
            assertEquals(state, reopened.replay("cancel-session-a", Long.MAX_VALUE).state());
        }
    }

    @Test void cancellationQueuedDuringRunningSurvivesCrashAndLeaseRecovery(@TempDir Path temp) {
        Path database = temp.resolve("runtime.db");
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T02:00:00Z"));
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(database)) {
            store.create(RunState.initial(spec("running-cancel", "session-running")));
            store.claim("running-cancel", "crashed-owner", clock.instant(),
                    Duration.ofSeconds(10)).orElseThrow();
            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, waitingExecutor(),
                    null, clock, "submitter", Duration.ofSeconds(10))) {
                RunView submitted = new RunCancellationService(runtime, runtime, clock)
                        .cancelSession("session-running", "manual_stop").get(0);
                assertEquals(RunStatus.RUNNING, submitted.state().status());
                assertEquals(1, store.pendingInbox("running-cancel").size());
            }
        }

        clock.advance(Duration.ofSeconds(11));
        AtomicInteger normalExecutions = new AtomicInteger();
        PhaseExecutor mustNotRun = context -> {
            normalExecutions.incrementAndGet();
            return PhaseResult.route(RuntimePhase.MODEL);
        };
        try (SqliteDurableRuntimeStore reopened = new SqliteDurableRuntimeStore(database);
             LocalDurableAgentRuntime recovered = new LocalDurableAgentRuntime(reopened, mustNotRun,
                     null, clock, "recovered-owner", Duration.ofSeconds(10))) {
            recovered.runScheduledWork();
            RunState cancelled = recovered.get("running-cancel").orElseThrow().state();
            assertEquals(RunStatus.CANCELLED, cancelled.status());
            assertTrue(cancelled.cancelRequested());
            assertEquals(0, normalExecutions.get(),
                    "a durable CancelRequested must win before normal phase execution resumes");
            assertEquals(cancelled, recovered.replayState("running-cancel", Long.MAX_VALUE).state());
        }
    }

    private static RunSpec spec(String runId, String sessionId) {
        return new RunSpec(runId, "", runId, "", List.of(), "wait", "default", 16,
                Map.of("sessionId", sessionId));
    }

    private static PhaseExecutor waitingExecutor() {
        return new ricbot.application.runtime.FixedPhaseExecutor(Map.of(RuntimePhase.MODEL,
                context -> new PhaseResult(List.of(), List.of(new RuntimeCommand.Suspend(
                        new WaitReason.UserInputWait("input", "wait"))))));
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        void advance(Duration duration) { value = value.plus(duration); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
