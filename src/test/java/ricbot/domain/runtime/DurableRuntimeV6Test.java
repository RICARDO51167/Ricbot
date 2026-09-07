package ricbot.domain.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.application.runtime.FixedPhaseExecutor;
import ricbot.application.runtime.LocalDurableAgentRuntime;
import ricbot.application.runtime.EffectRuntime;
import ricbot.application.runtime.ModelInvocationRuntime;
import ricbot.infra.runtime.SqliteDurableRuntimeStore;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class DurableRuntimeV6Test {
    @Test void maxSuperstepsAppliesAcrossWaitAndProcessRestart(@TempDir Path temp) {
        Path database = temp.resolve("step-limit.db");
        try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(
                new SqliteDurableRuntimeStore(database), new FixedPhaseExecutor(Map.of()))) {
            RunView waiting = runtime.start(new RunSpec("limited", "", "limited", "", List.of(),
                    "wait", "default", 3, Map.of()));
            assertEquals(RunStatus.WAITING, waiting.state().status());
            assertEquals(3, waiting.state().superstep());
        }

        try (LocalDurableAgentRuntime recovered = new LocalDurableAgentRuntime(
                new SqliteDurableRuntimeStore(database), new FixedPhaseExecutor(Map.of()))) {
            RunView failed = recovered.submit("limited", new ExternalEvent.UserMessage(
                    "resume-limited", "input:limited", Instant.now(), Map.of("text", "continue")));
            assertEquals(RunStatus.FAILED, failed.state().status());
            assertEquals("MAX_SUPERSTEPS_EXCEEDED", failed.state().failureCode());
            assertEquals(3, failed.state().superstep());
            assertEquals(failed.state(), recovered.replayState("limited", Long.MAX_VALUE).state());
        }
    }

    @Test void idleSchedulerDoesNotAllocateTransactions(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("idle.db");
        try (LocalDurableAgentRuntime ignored = new LocalDurableAgentRuntime(
                new SqliteDurableRuntimeStore(database), new FixedPhaseExecutor(Map.of()))) {
            long before = transactionCount(database);
            Thread.sleep(750);
            assertEquals(before, transactionCount(database));
        }
    }

    @Test void schedulerHealthReportsFailureAndClearsItAfterRecovery(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("health.db");
        Path saved = temp.resolve("health.saved.db");
        try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(
                new SqliteDurableRuntimeStore(database), new FixedPhaseExecutor(Map.of()))) {
            awaitCondition(() -> runtime.health().lastSuccessfulTick() != null, Duration.ofSeconds(3));
            Files.move(database, saved);
            Files.createDirectory(database);
            awaitCondition(() -> runtime.health().consecutiveFailures() > 0, Duration.ofSeconds(3));
            RuntimeHealth failed = runtime.health();
            assertNotNull(failed.lastFailedTick());
            assertFalse(failed.lastFailure().isBlank());

            Files.delete(database);
            Files.move(saved, database);
            awaitCondition(() -> runtime.health().lastSuccessfulTick() != null
                    && runtime.health().consecutiveFailures() == 0, Duration.ofSeconds(7));
            assertEquals("", runtime.health().lastFailure());
        }
    }

    @Test void readyRunQueriesAreBoundedAndDoNotIncludeTerminalHistory(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("bounded.db"))) {
            for (int i = 0; i < 70; i++) {
                store.create(RunState.initial(new RunSpec("ready-" + i, "", "ready-" + i, "", List.of(),
                        "ready", "default", 16, Map.of())));
            }
            assertEquals(64, store.readyRuns(64).size());
            assertTrue(store.readyRuns(1).stream().allMatch(view -> view.state().status() == RunStatus.READY));
        }
    }

    @Test void dueTimerQueriesAreBoundedAndExcludeFutureRetries(@TempDir Path temp) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("timers.db"))) {
            for (int i = 0; i < 70; i++) {
                String runId = "due-" + i;
                store.create(RunState.initial(new RunSpec(runId, "", runId, "", List.of(),
                        "retry", "default", 16, Map.of())));
                commit(store, runId, List.of(), List.of(new RuntimeCommand.Suspend(
                        new WaitReason.RetryWait("retry:" + runId, now.minusSeconds(1), 1))));
            }
            store.create(RunState.initial(new RunSpec("future", "", "future", "", List.of(),
                    "retry", "default", 16, Map.of())));
            commit(store, "future", List.of(), List.of(new RuntimeCommand.Suspend(
                    new WaitReason.RetryWait("retry:future", now.plusSeconds(60), 1))));

            assertEquals(64, store.dueTimers(now, 64).size());
            assertTrue(store.dueTimers(now, 64).stream()
                    .noneMatch(view -> view.state().spec().runId().equals("future")));
        }
    }
    @Test void everySerializedExternalEventCommitHasIdenticalLiveAndReplayProjection(
            @TempDir Path temp) throws Exception {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        List<EventCase> cases = List.of(
                new EventCase("user", new WaitReason.UserInputWait("c-user", "question"),
                        new ExternalEvent.UserMessage("e-user", "c-user", now, Map.of("text", "yes")), List.of()),
                new EventCase("steer", new WaitReason.UserInputWait("c-steer", "question"),
                        new ExternalEvent.SteeringMessage("e-steer", "c-steer", now, Map.of("text", "change")), List.of()),
                new EventCase("approval", new WaitReason.ApprovalWait("approval-1", "c-approval"),
                        new ExternalEvent.ApprovalDecision("e-approval", "c-approval", now,
                                Map.of("requestId", "approval-1", "approved", true)), List.of()),
                new EventCase("child", new WaitReason.ChildRunWait("c-child", List.of("child-1")),
                        new ExternalEvent.ChildRunCompleted("e-child", "child-1", now,
                                Map.of("childRunId", "child-1", "status", "COMPLETED")), List.of()),
                new EventCase("timer", new WaitReason.RetryWait("c-timer", now, 1),
                        new ExternalEvent.TimerExpired("e-timer", "c-timer", now, Map.of("attempt", 1)), List.of()),
                new EventCase("external", new WaitReason.ExternalEventWait("c-external",
                        "ExternalActionResult", Map.of("effectId", "effect-external")),
                        new ExternalEvent.ExternalActionResult("e-external", "c-external", now,
                                Map.of("effectId", "effect-external", "outcome", "SUCCEEDED")), List.of()),
                new EventCase("effect", new WaitReason.ExternalEventWait("effect-confirm",
                        "EffectConfirmation", Map.of("effectId", "effect-confirm")),
                        new ExternalEvent.EffectConfirmation("e-effect", "effect-confirm", now,
                                Map.of("effectId", "effect-confirm", "outcome", "FAILED")), List.of()),
                new EventCase("cancel", new WaitReason.UserInputWait("c-cancel", "question"),
                        new ExternalEvent.CancelRequested("e-cancel", "c-cancel", now, Map.of()),
                        List.of(new RuntimeCommand.CancelAtBoundary("c-cancel", List.of())))
        );
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        for (EventCase eventCase : cases) {
            Path database = temp.resolve(eventCase.name() + ".db");
            try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(database)) {
                String runId = "event-" + eventCase.name();
                store.create(RunState.initial(new RunSpec(runId, "", runId, "", List.of(),
                        eventCase.name(), "default", 16, Map.of())));
                commit(store, runId, List.of(), List.of(new RuntimeCommand.Suspend(eventCase.waitReason())));
                if (eventCase.event() instanceof ExternalEvent.ExternalActionResult) {
                    EffectIntent intent = effectIntent("effect-external", runId);
                    store.saveEffect(new EffectRecord(intent, EffectRecord.Status.PREPARED, 1, -1,
                            Map.of(), "", "", now));
                    store.saveEffect(new EffectRecord(intent, EffectRecord.Status.DISPATCHING, 1, -1,
                            Map.of(), "", "", now));
                } else if (eventCase.event() instanceof ExternalEvent.EffectConfirmation) {
                    EffectIntent intent = effectIntent("effect-confirm", runId);
                    store.saveEffect(new EffectRecord(intent, EffectRecord.Status.PREPARED, 1, -1,
                            Map.of(), "", "", now));
                    store.saveEffect(new EffectRecord(intent, EffectRecord.Status.DISPATCHING, 1, -1,
                            Map.of(), "", "", now));
                    store.saveEffect(new EffectRecord(intent, EffectRecord.Status.UNKNOWN, 1, -1,
                            Map.of(), "", "unknown", now));
                }
                ExternalEvent serialized = mapper.readerFor(ExternalEvent.class).readValue(
                        mapper.writerFor(ExternalEvent.class).writeValueAsString(eventCase.event()));
                RunState before = store.get(runId).orElseThrow().state();
                RunState live = store.acceptEvent(ExternalEventBatch.single(ExternalEventCommit.reduce(
                        before, serialized, eventCase.commands(), new RuntimeReducer())));
                assertEquals(live, store.replay(runId, Long.MAX_VALUE).state(), eventCase.name());
            }
        }
    }

    @Test void externalEventBatchRollsBackEveryRunWhenOneCasFails(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            for (String runId : List.of("batch-a", "batch-b")) {
                store.create(RunState.initial(new RunSpec(runId, "", runId, "", List.of(),
                        "wait", "default", 16, Map.of())));
                commit(store, runId, List.of(), List.of(new RuntimeCommand.Suspend(
                        new WaitReason.UserInputWait("input:" + runId, "wait"))));
            }
            RunState a = store.get("batch-a").orElseThrow().state();
            RunState b = store.get("batch-b").orElseThrow().state();
            ExternalEventCommit first = ExternalEventCommit.reduce(a,
                    new ExternalEvent.UserMessage("batch-event-a", "input:batch-a", Instant.now(),
                            Map.of("text", "a")), List.of(), new RuntimeReducer());
            ExternalEventCommit validSecond = ExternalEventCommit.reduce(b,
                    new ExternalEvent.UserMessage("batch-event-b", "input:batch-b", Instant.now(),
                            Map.of("text", "b")), List.of(), new RuntimeReducer());
            ExternalEventCommit invalidSecond = new ExternalEventCommit(validSecond.runId(),
                    validSecond.expectedCommitSequence(), "invalid-digest", validSecond.event(),
                    validSecond.commands(), validSecond.reduction());

            assertThrows(IllegalStateException.class, () -> store.acceptEvent(
                    new ExternalEventBatch(List.of(first, invalidSecond))));
            assertEquals(a, store.get("batch-a").orElseThrow().state());
            assertEquals(b, store.get("batch-b").orElseThrow().state());
            assertTrue(store.pendingInbox("batch-a").isEmpty());
            assertTrue(store.pendingInbox("batch-b").isEmpty());
        }
    }

    @Test void reducerIsDeterministicAndUsesOnlyWritesAndCommands() {
        RunState running = running(RunState.initial(new RunSpec("r1", "", "", "", List.of(),
                "goal", "default", 16, Map.of())));
        List<ChannelWrite> writes = List.of(ChannelWrite.set("answer", "ok"));
        List<RuntimeCommand> commands = List.of(new RuntimeCommand.Complete(Map.of("text", "done")));
        RuntimeReducer reducer = new RuntimeReducer();

        Reduction first = reducer.reduce(running, writes, commands);
        Reduction second = reducer.reduce(running, writes, commands);

        assertEquals(first, second);
        assertEquals(RunStatus.COMPLETED, first.state().status());
        assertEquals(1, first.state().superstep());
        assertEquals(1, first.state().commitSequence());
    }

    @Test void subscriberFailureCannotTurnACommittedMutationIntoAnApparentRollback(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            AtomicInteger delivered = new AtomicInteger();
            store.subscribe(event -> { throw new IllegalStateException("broken telemetry sink"); });
            store.subscribe(event -> delivered.incrementAndGet());

            RunState created = store.create(RunState.initial(new RunSpec("subscriber-isolation", "",
                    "subscriber-isolation", "", List.of(), "goal", "default", 16, Map.of())));

            assertEquals(RunStatus.READY, created.status());
            assertEquals(created, store.get("subscriber-isolation").orElseThrow().state());
            assertEquals(1, delivered.get(), "a broken subscriber must not suppress healthy subscribers");
        }
    }

    @Test void runtimeStopsAtWaitThenConsumesEventAndReplayMatches(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"));
             LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                     new FixedPhaseExecutor(Map.of(RuntimePhase.MODEL, context -> {
                         if (context.state().channels().containsKey("lastExternalEvents")) {
                             return new PhaseResult(List.of(ChannelWrite.set("answer", "accepted")),
                                     List.of(new RuntimeCommand.Complete(Map.of("answer", "accepted"))));
                         }
                         return new PhaseResult(List.of(), List.of(new RuntimeCommand.Suspend(
                                 new WaitReason.UserInputWait("question", "continue?"))));
                     })))) {
            RunView waiting = runtime.start(new RunSpec("run-a", "", "", "", List.of(),
                    "test", "default", 32, Map.of()));
            assertEquals(RunStatus.WAITING, waiting.state().status());

            RunView completed = runtime.submit("run-a", new ExternalEvent.UserMessage("event-1", "question",
                    Instant.parse("2026-01-01T00:00:00Z"), Map.of("text", "yes")));
            assertEquals(RunStatus.COMPLETED, completed.state().status());
            assertEquals("accepted", completed.state().channels().get("answer"));

            StateReplay replay = runtime.replayState("run-a", Long.MAX_VALUE);
            assertEquals(completed.projectionDigest(), replay.projectionDigest());
            assertEquals(completed.state(), replay.state());
            assertTrue(runtime.events("run-a").stream().noneMatch(event -> event.type().contains("STREAM")));
        }
    }

    @Test void resourceClaimsAreExclusiveAcrossConnections(@TempDir Path temp) {
        Path db = temp.resolve("runtime.db");
        try (SqliteDurableRuntimeStore first = new SqliteDurableRuntimeStore(db);
             SqliteDurableRuntimeStore second = new SqliteDurableRuntimeStore(db)) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            assertTrue(first.acquireResources("effect-a", "owner-a", List.of("file:/a", "git:/repo"),
                    now, java.time.Duration.ofMinutes(1)));
            assertFalse(second.acquireResources("effect-b", "owner-b", List.of("file:/a"),
                    now, java.time.Duration.ofMinutes(1)));
            first.releaseResources("effect-a", "owner-a");
            assertTrue(second.acquireResources("effect-b", "owner-b", List.of("file:/a"),
                    now, java.time.Duration.ofMinutes(1)));
        }
    }

    @Test void childJoinWaitsForAllAndDagDependenciesGateActivation(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunSpec parent = new RunSpec("parent", "", "parent", "", List.of(),
                    "parent", "default", 32, Map.of());
            RunSpec first = new RunSpec("child-a", "parent", "parent", "", List.of(),
                    "first", "default", 16, Map.of());
            RunSpec second = new RunSpec("child-b", "parent", "parent", "", List.of("child-a"),
                    "second", "default", 16, Map.of());
            store.create(RunState.initial(parent));

            commit(store, "parent", List.of(), List.of(new RuntimeCommand.SpawnChildRuns(
                    List.of(first, second), true)));
            RunState joined = store.get("parent").orElseThrow().state();
            assertEquals(RunStatus.WAITING, joined.status());
            assertEquals(List.of("child-a", "child-b"),
                    ((WaitReason.ChildRunWait) joined.waitReason()).childRunIds());
            assertEquals(RunStatus.READY, store.get("child-a").orElseThrow().state().status());
            assertEquals(RunStatus.WAITING, store.get("child-b").orElseThrow().state().status());

            commit(store, "child-a", List.of(), List.of(new RuntimeCommand.Complete(Map.of("value", "a"))));
            RunState afterFirst = store.get("parent").orElseThrow().state();
            assertEquals(RunStatus.WAITING, afterFirst.status());
            assertEquals(List.of("child-b"), ((WaitReason.ChildRunWait) afterFirst.waitReason()).childRunIds());
            assertEquals(RunStatus.READY, store.get("child-b").orElseThrow().state().status());

            commit(store, "child-b", List.of(), List.of(new RuntimeCommand.Complete(Map.of("value", "b"))));
            RunState ready = store.get("parent").orElseThrow().state();
            assertEquals(RunStatus.READY, ready.status());
            assertEquals(2, store.pendingInbox("parent").size());
            assertEquals(ready, store.replay("parent", Long.MAX_VALUE).state());
            assertEquals(2, store.events("parent").stream()
                    .filter(event -> event.type().equals("CHILD_RUN_COMPLETED")).count());
        }
    }

    @Test void recursiveForkRewritesDependenciesCopiesFactsAndRequiresOneConfirmation(@TempDir Path temp) {
        Path workspace = temp.resolve("workspace");
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(workspace.resolve(".ricbot/runtime.db"));
             SqliteRuntimeStore transcripts = new SqliteRuntimeStore(workspace)) {
            RunSpec parent = new RunSpec("source", "", "source", "", List.of(),
                    "parent", "default", 64, Map.of());
            RunSpec first = new RunSpec("source-a", "source", "source", "", List.of(),
                    "first", "default", 16, Map.of());
            RunSpec dependent = new RunSpec("source-b", "source", "source", "", List.of("source-a"),
                    "dependent", "default", 16, Map.of());
            RunSpec completed = new RunSpec("source-c", "source", "source", "", List.of(),
                    "completed", "default", 16, Map.of());
            transcripts.initialize("source", List.of(Map.of("role", "user", "content", "fork me")));
            store.create(RunState.initial(parent));
            commit(store, "source", List.of(ChannelWrite.set("transcriptCursor", 1L)),
                    List.of(new RuntimeCommand.SpawnChildRuns(List.of(first, dependent, completed), true)));
            commit(store, "source-c", List.of(), List.of(new RuntimeCommand.Complete(Map.of("value", "done"))));
            commit(store, "source-a", List.of(), List.of(new RuntimeCommand.Suspend(
                    new WaitReason.UserInputWait("source-a-input", "pause"))));

            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                    new FixedPhaseExecutor(Map.of()), transcripts, java.time.Clock.systemUTC(),
                    "fork-test", Duration.ofSeconds(30))) {
                RunView fork = runtime.fork(new ForkSpec("source", 2, "forked", false));
                assertEquals(RunStatus.WAITING, fork.state().status());
                assertTrue(fork.state().waitReason() instanceof WaitReason.ApprovalWait);
                assertEquals(List.of(Map.of("role", "user", "content", "fork me")), transcripts.read("forked"));
                assertEquals(2, runtime.children("forked").size());

                RunState clonedA = runtime.children("forked").stream().map(RunView::state)
                        .filter(state -> "source-a".equals(state.spec().metadata().get("forkedFromRunId")))
                        .findFirst().orElseThrow();
                RunState clonedB = runtime.children("forked").stream().map(RunView::state)
                        .filter(state -> "source-b".equals(state.spec().metadata().get("forkedFromRunId")))
                        .findFirst().orElseThrow();
                assertEquals(List.of(clonedA.spec().runId()), clonedB.spec().dependencies());
                assertTrue(((List<?>) fork.state().channels().get("completedChildFacts")).stream()
                        .map(value -> (Map<?, ?>) value)
                        .anyMatch(value -> "source-c".equals(value.get("sourceRunId"))));
                assertTrue(runtime.children("forked").stream()
                        .allMatch(view -> view.state().waitReason() instanceof WaitReason.ApprovalWait));

                runtime.submit("forked", new ExternalEvent.ApprovalDecision("approve-fork", "fork:forked",
                        Instant.now(), Map.of("approved", true)));
                RunState dependentAfterApproval = runtime.get(clonedB.spec().runId()).orElseThrow().state();
                assertTrue(dependentAfterApproval.waitReason() instanceof WaitReason.ChildRunWait);
                assertEquals(List.of(clonedA.spec().runId()),
                        ((WaitReason.ChildRunWait) dependentAfterApproval.waitReason()).childRunIds());
            }
        }
    }

    @Test void historicalForkUsesDescendantStateAtTheRequestedRootCommit(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunSpec root = new RunSpec("historical-root", "", "historical-root", "", List.of(),
                    "root", "default", 32, Map.of());
            RunSpec child = new RunSpec("historical-child", "historical-root", "historical-root", "",
                    List.of(), "child", "default", 32, Map.of());
            store.create(RunState.initial(root));
            RunState rootAtSpawn = commit(store, "historical-root", List.of(),
                    List.of(new RuntimeCommand.SpawnChildRuns(List.of(child), true)));
            commit(store, "historical-child", List.of(),
                    List.of(new RuntimeCommand.Complete(Map.of("value", "completed later"))));

            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                    new FixedPhaseExecutor(Map.of()))) {
                RunView fork = runtime.fork(new ForkSpec("historical-root",
                        rootAtSpawn.commitSequence(), "historical-fork", false));

                assertEquals(1, runtime.children(fork.state().spec().runId()).size(),
                        "a child that completed after the fork cutoff must remain an incomplete clone");
                assertFalse(fork.state().channels().containsKey("completedChildFacts"));
            }
        }
    }

    @Test void historicalForkUsesCompletedDependenciesAsFactsWithoutSourceReferences(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunSpec root = new RunSpec("dependency-root", "", "dependency-root", "", List.of(),
                    "root", "default", 32, Map.of());
            RunSpec completed = new RunSpec("dependency-fact", "dependency-root", "dependency-root", "",
                    List.of(), "fact", "default", 32, Map.of());
            RunSpec consumer = new RunSpec("dependency-consumer", "dependency-root", "dependency-root", "",
                    List.of("dependency-fact"), "consumer", "default", 32, Map.of());
            store.create(RunState.initial(root));
            commit(store, "dependency-root", List.of(),
                    List.of(new RuntimeCommand.SpawnChildRuns(List.of(completed, consumer), true)));
            commit(store, "dependency-fact", List.of(),
                    List.of(new RuntimeCommand.Complete(Map.of("value", "immutable"))));
            long cutoff = store.get("dependency-root").orElseThrow().state().commitSequence();

            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                    new FixedPhaseExecutor(Map.of()))) {
                runtime.fork(new ForkSpec("dependency-root", cutoff, "dependency-fork", false));
                RunState clone = runtime.children("dependency-fork").stream().map(RunView::state)
                        .filter(state -> "dependency-consumer".equals(
                                state.spec().metadata().get("forkedFromRunId")))
                        .findFirst().orElseThrow();

                assertTrue(clone.spec().dependencies().isEmpty());
                assertTrue(((List<?>) clone.channels().get("completedChildFacts")).stream()
                        .map(Map.class::cast)
                        .anyMatch(fact -> "dependency-fact".equals(fact.get("sourceRunId"))));
            }
        }
    }

    @Test void historicalForkUnknownGateUsesTheCutoffProjection(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            store.create(RunState.initial(new RunSpec("unknown-root", "", "unknown-root", "", List.of(),
                    "root", "default", 32, Map.of())));
            RunState safe = commit(store, "unknown-root", List.of(ChannelWrite.set("safe", true)),
                    List.of(new RuntimeCommand.Suspend(new WaitReason.UserInputWait("before-effect", "wait"))));
            store.acceptEvent(ExternalEventBatch.single(ExternalEventCommit.reduce(
                    store.get("unknown-root").orElseThrow().state(),
                    new ExternalEvent.UserMessage("resume-effect", "before-effect", Instant.now(), Map.of("text", "go")),
                    List.of(), new RuntimeReducer())));
            commit(store, "unknown-root", List.of(), List.of(new RuntimeCommand.Suspend(
                    new WaitReason.ExternalEventWait("unknown-effect", "EffectConfirmation",
                            Map.of("effectId", "unknown-effect")))));

            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                    new FixedPhaseExecutor(Map.of()))) {
                assertDoesNotThrow(() -> runtime.fork(new ForkSpec("unknown-root",
                        safe.commitSequence(), "before-unknown", false)));
                assertThrows(IllegalStateException.class, () -> runtime.fork(new ForkSpec("unknown-root",
                        Long.MAX_VALUE, "at-unknown", false)));
            }
        }
    }

    @Test void forkCleansEveryCopiedTranscriptPrefixWhenACopyFails(@TempDir Path temp) {
        FailingTranscriptPort transcripts = new FailingTranscriptPort(2);
        transcripts.initialize("copy-root", List.of(Map.of("role", "user", "content", "root")));
        transcripts.initialize("copy-child", List.of(Map.of("role", "user", "content", "child")));
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            store.create(RunState.initial(new RunSpec("copy-root", "", "copy-root", "", List.of(),
                    "root", "default", 32, Map.of())));
            commit(store, "copy-root", List.of(ChannelWrite.set("transcriptCursor", 1L)),
                    List.of(new RuntimeCommand.SpawnChildRuns(List.of(new RunSpec("copy-child", "copy-root",
                            "copy-root", "", List.of(), "child", "default", 16,
                            Map.of("transcriptCursor", 1L))), true)));
            RunState child = store.get("copy-child").orElseThrow().state();
            RunState childWithCursor = new RuntimeReducer().reduce(running(child),
                    List.of(ChannelWrite.set("transcriptCursor", 1L)),
                    List.of(new RuntimeCommand.Suspend(new WaitReason.UserInputWait("copy", "wait")))).state();
            // Persist the child cursor through the normal commit path.
            Activation activation = store.claim("copy-child", "copy-owner", Instant.now(),
                    Duration.ofMinutes(1)).orElseThrow();
            store.commit(new CommitBatch(activation, store.get("copy-child").orElseThrow().state(),
                    new Reduction(childWithCursor, List.of()), List.of(ChannelWrite.set("transcriptCursor", 1L)),
                    List.of(new RuntimeCommand.Suspend(new WaitReason.UserInputWait("copy", "wait"))),
                    List.of(), "TEST_COMMIT", Map.of(), Instant.now()));

            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                    new FixedPhaseExecutor(Map.of()), transcripts, Clock.systemUTC(), "copy-owner",
                    Duration.ofSeconds(30))) {
                assertThrows(IllegalStateException.class, () -> runtime.fork(new ForkSpec(
                        "copy-root", Long.MAX_VALUE, "copy-fork", false)));
                assertFalse(transcripts.contains("copy-fork"));
                assertEquals(Set.of("copy-fork"), transcripts.cleaned());
                assertTrue(runtime.get("copy-fork").isEmpty());
            }
        }
    }

    @Test void observedModelResponseIsReusedAfterCrash(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            store.create(RunState.initial(RunSpec.root("model crash")));
            String runId = store.list().get(0).state().spec().runId();
            AtomicInteger dispatches = new AtomicInteger();
            ModelInvocationRuntime.ModelProviderPort provider = call -> {
                dispatches.incrementAndGet();
                return new ModelInvocationRuntime.ModelObservation("provider-1", "response-1",
                        Map.of("content", "durable"));
            };
            ModelInvocationRuntime.ModelCall call = new ModelInvocationRuntime.ModelCall(
                    "invocation:attempt-1", runId, "activation", 1, 10,
                    Map.of("prompt", "hello"), false, ModelInvocation.UnknownPolicy.RECONCILE_THEN_WAIT);
            ModelInvocationRuntime crashing = new ModelInvocationRuntime(store, provider,
                    java.time.Clock.systemUTC(), CrashInjector.failOnce(
                    CrashInjector.Point.MODEL_AFTER_OBSERVATION_PERSIST));

            assertThrows(CrashInjector.InjectedCrash.class, () -> crashing.invoke(call));
            ModelInvocation recovered = new ModelInvocationRuntime(store, provider,
                    java.time.Clock.systemUTC()).invoke(call);
            assertEquals(ModelInvocation.Status.OBSERVED, recovered.status());
            assertEquals("durable", recovered.response().get("content"));
            assertEquals(1, dispatches.get());
        }
    }

    @Test void modelCrashBeforeObservationNeverRedispatchesTheSameAttempt(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunState run = store.create(RunState.initial(RunSpec.root("model crash before persistence")));
            AtomicInteger dispatches = new AtomicInteger();
            ModelInvocationRuntime.ModelProviderPort provider = call -> {
                dispatches.incrementAndGet();
                return new ModelInvocationRuntime.ModelObservation("provider-1", "response-1",
                        Map.of("content", "possibly delivered"));
            };
            ModelInvocationRuntime.ModelCall first = new ModelInvocationRuntime.ModelCall(
                    "invocation:attempt-1", run.spec().runId(), "activation-1", 1, 10,
                    Map.of("prompt", "hello"), false, ModelInvocation.UnknownPolicy.RECONCILE_THEN_RETRY);
            ModelInvocationRuntime crashing = new ModelInvocationRuntime(store, provider,
                    Clock.systemUTC(), CrashInjector.failOnce(CrashInjector.Point.MODEL_BEFORE_OBSERVATION_PERSIST));

            assertThrows(CrashInjector.InjectedCrash.class, () -> crashing.invoke(first));
            ModelInvocation recovered = new ModelInvocationRuntime(store, provider, Clock.systemUTC()).invoke(first);
            assertEquals(ModelInvocation.Status.UNKNOWN, recovered.status());
            assertEquals(1, dispatches.get(), "the uncertain attempt must never be dispatched twice");

            ModelInvocationRuntime.ModelCall second = new ModelInvocationRuntime.ModelCall(
                    "invocation:attempt-2", run.spec().runId(), "activation-2", 2, 10,
                    Map.of("prompt", "hello"), true, ModelInvocation.UnknownPolicy.RECONCILE_THEN_RETRY);
            ModelInvocation observed = new ModelInvocationRuntime(store, provider, Clock.systemUTC()).invoke(second);
            assertEquals(ModelInvocation.Status.OBSERVED, observed.status());
            assertTrue(observed.possibleDuplicateCharge());
            assertEquals(2, dispatches.get());
        }
    }

    @Test void modelRequestIdentityIsDurableBeforeDispatchAndCancellationInterruptsTheCall(@TempDir Path temp)
            throws Exception {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunState run = store.create(RunState.initial(RunSpec.root("cancel model")));
            CountDownLatch entered = new CountDownLatch(1);
            AtomicReference<String> durableRequestId = new AtomicReference<>();
            AtomicReference<String> cancelledRequestId = new AtomicReference<>();
            ModelInvocationRuntime.ModelProviderPort provider = new ModelInvocationRuntime.ModelProviderPort() {
                @Override public String prepareRequestId(ModelInvocationRuntime.ModelCall call) {
                    return "provider-request-42";
                }
                @Override public ModelInvocationRuntime.ModelObservation dispatch(
                        ModelInvocationRuntime.ModelCall call) {
                    durableRequestId.set(store.modelInvocation(call.invocationId()).orElseThrow()
                            .providerRequestId());
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("dispatch should have been interrupted");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ModelInvocationRuntime.UncertainDispatchException(
                                "cancelled during dispatch", "provider-request-42", interrupted);
                    }
                }
                @Override public void cancel(String providerRequestId) {
                    cancelledRequestId.set(providerRequestId);
                }
            };
            ModelInvocationRuntime models = new ModelInvocationRuntime(store, provider, Clock.systemUTC());
            ModelInvocationRuntime.ModelCall call = new ModelInvocationRuntime.ModelCall("cancel-invocation",
                    run.spec().runId(), "activation", 1, 42, Map.of("prompt", "hello"), false,
                    ModelInvocation.UnknownPolicy.RECONCILE_THEN_WAIT);
            CompletableFuture<ModelInvocation> result = CompletableFuture.supplyAsync(() -> models.invoke(call));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            models.cancel(run.spec().runId());
            ModelInvocation invocation = result.get(5, TimeUnit.SECONDS);

            assertEquals("provider-request-42", durableRequestId.get());
            assertEquals("provider-request-42", cancelledRequestId.get());
            assertEquals(ModelInvocation.Status.UNKNOWN, invocation.status());
        }
    }

    @Test void genericWriteDispatchFailureIsUnknownUntilReconciledOrConfirmed(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunState run = store.create(RunState.initial(RunSpec.root("ambiguous write")));
            EffectIntent intent = new EffectIntent("effect-ambiguous", run.spec().runId(), "activation",
                    "write", "args", "key", List.of("file:/ambiguous"), Map.of(), "manual");
            EffectRuntime effects = new EffectRuntime(store, ignored -> {
                throw new IllegalStateException("connection closed after request bytes were sent");
            }, Clock.systemUTC(), "owner", Duration.ofSeconds(30));

            EffectRuntime.Outcome outcome = effects.execute(intent);

            assertTrue(outcome.requiresConfirmation());
            assertEquals(EffectRecord.Status.UNKNOWN, outcome.record().status());
            assertEquals("java.lang.IllegalStateException",
                    outcome.record().executionEvidence().get("exceptionType"));
        }
    }

    @Test void superstepBindsEveryEffectOutcomeToItsCommittedSuperstep(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            store.create(RunState.initial(new RunSpec("effect-bind", "", "effect-bind", "", List.of(),
                    "bind", "default", 16, Map.of())));
            Activation activation = store.claim("effect-bind", "owner", Instant.now(),
                    Duration.ofMinutes(1)).orElseThrow();
            for (EffectRecord.Status status : List.of(EffectRecord.Status.PREPARED,
                    EffectRecord.Status.UNKNOWN, EffectRecord.Status.FAILED, EffectRecord.Status.SUCCEEDED)) {
                String id = "effect-bind-" + status.name().toLowerCase();
                EffectIntent intent = new EffectIntent(id, "effect-bind", activation.activationId(), "write",
                        "digest", "key:" + id, List.of(), Map.of(), "manual");
                EffectRecord record = store.saveEffect(new EffectRecord(intent, EffectRecord.Status.PREPARED,
                        1, -1, Map.of(), "", "", Instant.now()));
                if (status == EffectRecord.Status.UNKNOWN || status == EffectRecord.Status.SUCCEEDED) {
                    record = store.saveEffect(new EffectRecord(intent, EffectRecord.Status.DISPATCHING,
                            1, -1, Map.of(), "", "", Instant.now()));
                }
                if (status != record.status()) {
                    store.saveEffect(new EffectRecord(intent, status, 1, -1, Map.of(),
                            status == EffectRecord.Status.SUCCEEDED ? "artifact:done" : "",
                            status == EffectRecord.Status.FAILED ? "failed" : "", Instant.now()));
                }
            }
            RunState running = store.get("effect-bind").orElseThrow().state();
            Reduction reduction = new RuntimeReducer().reduce(running, List.of(),
                    List.of(new RuntimeCommand.Complete(Map.of("ok", true))));
            store.commit(new CommitBatch(activation, running, reduction, List.of(),
                    List.of(new RuntimeCommand.Complete(Map.of("ok", true))), List.of(),
                    "TEST_COMMIT", Map.of(), Instant.now()));

            for (EffectRecord.Status status : List.of(EffectRecord.Status.PREPARED,
                    EffectRecord.Status.UNKNOWN, EffectRecord.Status.FAILED, EffectRecord.Status.SUCCEEDED)) {
                assertEquals(1L, store.effect("effect-bind-" + status.name().toLowerCase())
                        .orElseThrow().committedSuperstep(), status.name());
            }
        }
    }

    @Test void rootModelReservationsAreAtomicAcrossChildRuns(@TempDir Path temp) {
        Path database = temp.resolve("runtime.db");
        try (SqliteDurableRuntimeStore first = new SqliteDurableRuntimeStore(database);
             SqliteDurableRuntimeStore second = new SqliteDurableRuntimeStore(database)) {
            Map<String, Object> rootBudget = Map.of("maxTotalTokens", 4_000L, "finalizationTokens", 1_000L);
            Map<String, Object> metadata = Map.of("rootBudgetPolicy", rootBudget,
                    "budgetPolicy", Map.of("finalizationTokens", 0L));
            first.create(RunState.initial(new RunSpec("budget-root", "", "budget-root", "", List.of(),
                    "root", "default", 16, metadata)));
            first.create(RunState.initial(new RunSpec("budget-child-a", "budget-root", "budget-root", "",
                    List.of(), "a", "default", 16, metadata)));
            first.create(RunState.initial(new RunSpec("budget-child-b", "budget-root", "budget-root", "",
                    List.of(), "b", "default", 16, metadata)));
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Boolean> a = CompletableFuture.supplyAsync(() -> reserve(first,
                    prepared("budget-a", "budget-child-a", 1_800), start));
            CompletableFuture<Boolean> b = CompletableFuture.supplyAsync(() -> reserve(second,
                    prepared("budget-b", "budget-child-b", 1_800), start));
            start.countDown();

            assertEquals(1, List.of(a.join(), b.join()).stream().filter(Boolean::booleanValue).count());
            assertEquals(1, List.of(first.modelInvocation("budget-a"), first.modelInvocation("budget-b"))
                    .stream().filter(java.util.Optional::isPresent).count());
        }
    }

    @Test void rootCostReservationsAreAtomicAndUnpricedModelsFailClosed(@TempDir Path temp) {
        Path database = temp.resolve("runtime.db");
        try (SqliteDurableRuntimeStore first = new SqliteDurableRuntimeStore(database);
             SqliteDurableRuntimeStore second = new SqliteDurableRuntimeStore(database)) {
            Map<String, Object> pricing = Map.of("unitTokens", 1_000L,
                    "inputUsd", "0.001", "outputUsd", "0.001");
            Map<String, Object> rootBudget = Map.of("maxCostMicrousd", 1_500L, "finalizationTokens", 0L);
            Map<String, Object> metadata = Map.of("rootBudgetPolicy", rootBudget,
                    "budgetPolicy", Map.of("finalizationTokens", 0L), "modelPricing", pricing);
            first.create(RunState.initial(new RunSpec("cost-root", "", "cost-root", "", List.of(),
                    "root", "default", 16, metadata)));
            first.create(RunState.initial(new RunSpec("cost-a", "cost-root", "cost-root", "", List.of(),
                    "a", "default", 16, metadata)));
            first.create(RunState.initial(new RunSpec("cost-b", "cost-root", "cost-root", "", List.of(),
                    "b", "default", 16, metadata)));
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Boolean> a = CompletableFuture.supplyAsync(() -> reserve(first,
                    prepared("cost-invocation-a", "cost-a", 1_000), start));
            CompletableFuture<Boolean> b = CompletableFuture.supplyAsync(() -> reserve(second,
                    prepared("cost-invocation-b", "cost-b", 1_000), start));
            start.countDown();
            assertEquals(1, List.of(a.join(), b.join()).stream().filter(Boolean::booleanValue).count());

            Map<String, Object> unpriced = Map.of("rootBudgetPolicy", rootBudget,
                    "budgetPolicy", Map.of("finalizationTokens", 0L));
            first.create(RunState.initial(new RunSpec("cost-unpriced", "", "cost-unpriced", "", List.of(),
                    "unpriced", "default", 16, unpriced)));
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> first.saveModelInvocation(prepared("cost-unpriced-invocation", "cost-unpriced", 10)));
            assertTrue(rejected.getMessage().contains("cost_budget_unpriced"));
        }
    }

    @Test void rootToolAndActiveTimeReservationsAreAtomicAcrossConnections(@TempDir Path temp) {
        Path database = temp.resolve("runtime.db");
        try (SqliteDurableRuntimeStore first = new SqliteDurableRuntimeStore(database);
             SqliteDurableRuntimeStore second = new SqliteDurableRuntimeStore(database)) {
            Map<String, Object> rootBudget = Map.of("maxToolCalls", 1L, "maxActiveSeconds", 1L,
                    "finalizationTokens", 0L);
            Map<String, Object> metadata = Map.of("rootBudgetPolicy", rootBudget,
                    "budgetPolicy", Map.of("finalizationTokens", 0L));
            first.create(RunState.initial(new RunSpec("tool-root", "", "tool-root", "", List.of(),
                    "root", "default", 16, metadata)));
            first.create(RunState.initial(new RunSpec("tool-a", "tool-root", "tool-root", "", List.of(),
                    "a", "default", 16, metadata)));
            first.create(RunState.initial(new RunSpec("tool-b", "tool-root", "tool-root", "", List.of(),
                    "b", "default", 16, metadata)));
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Boolean> a = CompletableFuture.supplyAsync(() -> reserveTool(
                    first, "tool-a", "tool-reservation-a", start));
            CompletableFuture<Boolean> b = CompletableFuture.supplyAsync(() -> reserveTool(
                    second, "tool-b", "tool-reservation-b", start));
            start.countDown();
            assertEquals(1, List.of(a.join(), b.join()).stream().filter(Boolean::booleanValue).count());

            String accepted = reserveActive(first, "tool-a", "tool-reservation-a")
                    ? "tool-reservation-a" : "tool-reservation-b";
            SqliteDurableRuntimeStore acceptedStore = accepted.endsWith("a") ? first : second;
            String acceptedRun = accepted.endsWith("a") ? "tool-a" : "tool-b";
            if (accepted.endsWith("b")) {
                acceptedStore.reserveActiveTime(acceptedRun, accepted, 1_000L, Instant.now());
            }
            assertThrows(IllegalStateException.class,
                    () -> acceptedStore.reserveActiveTime(acceptedRun, accepted, 1_000L, Instant.now()));
            acceptedStore.settleActiveTime(accepted, 125L, Instant.now());
            acceptedStore.settleToolCall(accepted, Instant.now());
            BudgetUsage usage = first.budgetUsage("tool-root");
            assertEquals(1L, usage.toolCalls());
            assertEquals(125L, usage.activeMillis());
        }
    }

    @Test void uncertainEffectNeverRedispatchesSilently(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunState run = store.create(RunState.initial(RunSpec.root("effect crash")));
            AtomicInteger dispatches = new AtomicInteger();
            EffectRuntime.ToolEffectPort tool = intent -> {
                dispatches.incrementAndGet();
                return new EffectRuntime.EffectExecution("artifact:one", Map.of("receipt", "one"));
            };
            EffectIntent intent = new EffectIntent("effect-1", run.spec().runId(), "activation-1", "write",
                    "args", "key-1", List.of("file:/one"), Map.of("approved", true), "manual");
            EffectRuntime crashing = new EffectRuntime(store, tool, java.time.Clock.systemUTC(),
                    "owner-1", Duration.ofSeconds(30),
                    CrashInjector.failOnce(CrashInjector.Point.EFFECT_AFTER_DISPATCH));

            assertThrows(CrashInjector.InjectedCrash.class, () -> crashing.execute(intent));
            EffectRuntime.Outcome recovered = new EffectRuntime(store, tool, java.time.Clock.systemUTC(),
                    "owner-2", Duration.ofSeconds(30)).execute(intent);
            assertTrue(recovered.requiresConfirmation());
            assertEquals(EffectRecord.Status.UNKNOWN, recovered.record().status());
            assertEquals(1, dispatches.get());
        }
    }

    @Test void crashBeforeEffectDispatchRecoversAsUnknownWithoutCallingTheTool(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunState run = store.create(RunState.initial(RunSpec.root("effect crash before dispatch")));
            AtomicInteger dispatches = new AtomicInteger();
            EffectRuntime.ToolEffectPort tool = intent -> {
                dispatches.incrementAndGet();
                return new EffectRuntime.EffectExecution("artifact:one", Map.of());
            };
            EffectIntent intent = new EffectIntent("effect-before", run.spec().runId(), "activation-1", "write",
                    "args", "key-before", List.of("file:/one"), Map.of(), "manual");
            EffectRuntime crashing = new EffectRuntime(store, tool, Clock.systemUTC(), "owner-1",
                    Duration.ofSeconds(30), CrashInjector.failOnce(CrashInjector.Point.EFFECT_BEFORE_DISPATCH));

            assertThrows(CrashInjector.InjectedCrash.class, () -> crashing.execute(intent));
            EffectRuntime.Outcome recovered = new EffectRuntime(store, tool, Clock.systemUTC(), "owner-2",
                    Duration.ofSeconds(30)).execute(intent);
            assertTrue(recovered.requiresConfirmation());
            assertEquals(EffectRecord.Status.UNKNOWN, recovered.record().status());
            assertEquals(0, dispatches.get());
        }
    }

    @Test void resourceLeaseCrashBlocksUntilExpiryThenSafelyDispatches(@TempDir Path temp) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            RunState run = store.create(RunState.initial(RunSpec.root("resource lease crash")));
            AtomicInteger dispatches = new AtomicInteger();
            EffectRuntime.ToolEffectPort tool = intent -> {
                dispatches.incrementAndGet();
                return new EffectRuntime.EffectExecution("artifact:resource", Map.of("ok", true));
            };
            EffectIntent intent = new EffectIntent("effect-resource", run.spec().runId(), "activation-1", "write",
                    "args", "key-resource", List.of("file:/resource"), Map.of(), "manual");
            EffectRuntime crashing = new EffectRuntime(store, tool, clock, "owner-1", Duration.ofSeconds(10),
                    CrashInjector.failOnce(CrashInjector.Point.RESOURCE_LEASE_ACQUIRED));

            assertThrows(CrashInjector.InjectedCrash.class, () -> crashing.execute(intent));
            EffectRuntime.Outcome blocked = new EffectRuntime(store, tool, clock, "owner-2",
                    Duration.ofSeconds(10)).execute(intent);
            assertTrue(blocked.retryRequired());
            assertEquals(0, dispatches.get());

            clock.advance(Duration.ofSeconds(11));
            EffectRuntime.Outcome completed = new EffectRuntime(store, tool, clock, "owner-2",
                    Duration.ofSeconds(10)).execute(intent);
            assertEquals(EffectRecord.Status.SUCCEEDED, completed.record().status());
            assertEquals(1, dispatches.get());
        }
    }

    @Test void resourceLeaseCanBeRenewedAndOnlyExpiresAfterRenewedDeadline(@TempDir Path temp) {
        Path database = temp.resolve("runtime.db");
        try (SqliteDurableRuntimeStore first = new SqliteDurableRuntimeStore(database);
             SqliteDurableRuntimeStore second = new SqliteDurableRuntimeStore(database)) {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            assertTrue(first.acquireResources("effect-a", "owner-a", List.of("file:/a"), start,
                    Duration.ofSeconds(10)));
            assertTrue(first.renewResources("effect-a", "owner-a", List.of("file:/a"),
                    start.plusSeconds(8), Duration.ofSeconds(10)));
            assertFalse(second.acquireResources("effect-b", "owner-b", List.of("file:/a"),
                    start.plusSeconds(12), Duration.ofSeconds(10)));
            assertTrue(second.acquireResources("effect-b", "owner-b", List.of("file:/a"),
                    start.plusSeconds(19), Duration.ofSeconds(10)));
        }
    }

    @Test void activationClaimIsExclusiveAcrossConnectionsAndRecoversAfterExpiry(@TempDir Path temp) {
        Path database = temp.resolve("runtime.db");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        try (SqliteDurableRuntimeStore first = new SqliteDurableRuntimeStore(database);
             SqliteDurableRuntimeStore second = new SqliteDurableRuntimeStore(database)) {
            first.create(RunState.initial(new RunSpec("activation-race", "", "activation-race", "", List.of(),
                    "activation", "default", 16, Map.of())));
            Activation claimed = first.claim("activation-race", "owner-a", start,
                    Duration.ofSeconds(10)).orElseThrow();
            assertTrue(second.claim("activation-race", "owner-b", start,
                    Duration.ofSeconds(10)).isEmpty());
            assertEquals(RunStatus.RUNNING, second.get("activation-race").orElseThrow().state().status());

            assertEquals(1, second.recoverExpiredActivations(start.plusSeconds(11)));
            Activation recovered = second.claim("activation-race", "owner-b", start.plusSeconds(11),
                    Duration.ofSeconds(10)).orElseThrow();
            assertEquals(claimed.activationId(), recovered.activationId());
            assertEquals("owner-b", recovered.leaseOwner());
        }
    }

    @Test void activationLeaseCrashIsRecoveredWithoutCreatingADuplicateActivation(@TempDir Path temp) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            store.create(RunState.initial(new RunSpec("activation-crash", "", "activation-crash", "", List.of(),
                    "activation", "default", 16, Map.of())));
            ActivationScheduler crashing = new ActivationScheduler(store, "owner-a", clock, Duration.ofSeconds(10),
                    CrashInjector.failOnce(CrashInjector.Point.ACTIVATION_LEASE_ACQUIRED));
            assertThrows(CrashInjector.InjectedCrash.class, () -> crashing.claim("activation-crash"));
            assertEquals(RunStatus.RUNNING, store.get("activation-crash").orElseThrow().state().status());

            clock.advance(Duration.ofSeconds(11));
            ActivationScheduler recovered = new ActivationScheduler(store, "owner-b", clock, Duration.ofSeconds(10));
            Activation activation = recovered.claim("activation-crash").orElseThrow();
            assertEquals("owner-b", activation.leaseOwner());
            assertEquals(1, store.events("activation-crash").stream()
                    .filter(event -> event.type().equals("ACTIVATION_LEASE_EXPIRED")).count());
        }
    }

    @Test void eventSubmittedDuringRunningIsIngestedBeforeTerminal(@TempDir Path temp) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ingests = new AtomicInteger();
        FixedPhaseExecutor phases = new FixedPhaseExecutor(Map.of(
                RuntimePhase.INGEST, context -> {
                    if (ingests.incrementAndGet() == 1) {
                        entered.countDown();
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                    }
                    List<ChannelWrite> writes = context.inbox().isEmpty() ? List.of()
                            : List.of(ChannelWrite.set("acceptedEvents", context.inbox()));
                    return new PhaseResult(writes, List.of(new RuntimeCommand.Transition(RuntimePhase.CONTEXT)));
                },
                RuntimePhase.MODEL, context -> new PhaseResult(List.of(),
                        List.of(new RuntimeCommand.Complete(Map.of("ok", true))))));
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"));
             LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, phases)) {
            CountDownLatch enqueued = new CountDownLatch(1);
            AutoCloseable subscription = runtime.subscribe(event -> {
                if ("EXTERNAL_EVENT_ENQUEUED".equals(event.type())) enqueued.countDown();
            });
            RunSpec spec = new RunSpec("concurrent", "", "concurrent", "", List.of(),
                    "concurrent", "default", 32, Map.of());
            CompletableFuture<RunView> started = CompletableFuture.supplyAsync(() -> runtime.start(spec));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<RunView> submitted = CompletableFuture.supplyAsync(() -> runtime.submit("concurrent",
                    new ExternalEvent.SteeringMessage("steer-1", "concurrent", Instant.now(),
                            Map.of("text", "new direction"))));
            assertTrue(enqueued.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(RunStatus.COMPLETED, submitted.get(10, TimeUnit.SECONDS).state().status());
            assertEquals(RunStatus.COMPLETED, started.get(10, TimeUnit.SECONDS).state().status());
            assertEquals(2, ingests.get());
            assertEquals(1, ((List<?>) runtime.get("concurrent").orElseThrow().state()
                    .channels().get("acceptedEvents")).size());
            subscription.close();
        }
    }

    @Test void cancellationWaitsForUnknownWriteEffectThenTerminates(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"));
             LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                     new FixedPhaseExecutor(Map.of(RuntimePhase.MODEL, context -> new PhaseResult(List.of(),
                             List.of(new RuntimeCommand.Suspend(new WaitReason.UserInputWait("input", "wait")))))))) {
            RunView waiting = runtime.start(new RunSpec("cancel-effect", "", "cancel-effect", "", List.of(),
                    "cancel", "default", 32, Map.of()));
            EffectIntent intent = new EffectIntent("unknown-effect", "cancel-effect", "old-activation", "write",
                    "args", "cancel-key", List.of("file:/cancel"), Map.of(), "manual");
            EffectRecord prepared = store.saveEffect(new EffectRecord(intent, EffectRecord.Status.PREPARED,
                    1, -1, Map.of(), "", "", Instant.now()));
            EffectRecord dispatching = store.saveEffect(new EffectRecord(intent, EffectRecord.Status.DISPATCHING,
                    1, -1, Map.of(), "", "", Instant.now()));
            store.saveEffect(new EffectRecord(intent, EffectRecord.Status.UNKNOWN,
                    1, -1, Map.of("possible", true), "", "uncertain", Instant.now()));

            RunView cancelling = runtime.submit(waiting.state().spec().runId(),
                    new ExternalEvent.CancelRequested("cancel-1", "cancel-effect", Instant.now(), Map.of()));
            assertEquals(RunStatus.WAITING, cancelling.state().status());
            assertTrue(cancelling.state().cancelRequested());
            assertEquals("unknown-effect", cancelling.state().waitReason().correlationId());
            assertEquals(cancelling.state(), runtime.replayState("cancel-effect", Long.MAX_VALUE).state(),
                    "accepted cancellation must replay to the live WAITING projection");

            RunView cancelled = runtime.submit("cancel-effect", new ExternalEvent.EffectConfirmation(
                    "confirm-1", "unknown-effect", Instant.now(),
                    Map.of("effectId", "unknown-effect", "outcome", "SUCCEEDED")));
            assertEquals(RunStatus.CANCELLED, cancelled.state().status());
            assertEquals(EffectRecord.Status.SUCCEEDED, store.effect("unknown-effect").orElseThrow().status());
            assertEquals(cancelled.state(), runtime.replayState("cancel-effect", Long.MAX_VALUE).state());
        }
    }

    @Test void childParentHandoffRollsBackAsOneTransactionAtCrashPoint(@TempDir Path temp) {
        CrashInjector crashes = CrashInjector.failOnce(CrashInjector.Point.CHILD_PARENT_HANDOFF);
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"), crashes)) {
            RunSpec parent = new RunSpec("handoff-parent", "", "handoff-parent", "", List.of(),
                    "parent", "default", 16, Map.of());
            RunSpec child = new RunSpec("handoff-child", "handoff-parent", "handoff-parent", "", List.of(),
                    "child", "default", 16, Map.of());
            store.create(RunState.initial(parent));
            commit(store, "handoff-parent", List.of(),
                    List.of(new RuntimeCommand.SpawnChildRuns(List.of(child), true)));

            Instant now = Instant.now();
            Activation activation = store.claim("handoff-child", "owner", now, Duration.ofMinutes(1)).orElseThrow();
            RunState running = store.get("handoff-child").orElseThrow().state();
            List<RuntimeCommand> commands = List.of(new RuntimeCommand.Complete(Map.of("ok", true)));
            Reduction reduction = new RuntimeReducer().reduce(running, List.of(), commands);
            CommitBatch batch = new CommitBatch(activation, running, reduction, List.of(), commands, List.of(),
                    "TEST_COMMIT", Map.of(), now.plusMillis(1));

            assertThrows(CrashInjector.InjectedCrash.class, () -> store.commit(batch));
            assertEquals(RunStatus.RUNNING, store.get("handoff-child").orElseThrow().state().status());
            assertTrue(store.pendingInbox("handoff-parent").isEmpty());
            store.commit(batch);
            assertEquals(RunStatus.READY, store.get("handoff-parent").orElseThrow().state().status());
            assertEquals(1, store.pendingInbox("handoff-parent").size());
            assertEquals(1, store.events("handoff-parent").stream()
                    .filter(event -> event.type().equals("CHILD_RUN_COMPLETED")).count());
        }
    }

    @Test void stagedWritesAndPreCommitCrashesLeaveNoPartialState(@TempDir Path temp) {
        assertPreCommitCrash(temp.resolve("pending.db"), CrashInjector.Point.PENDING_WRITE_STAGED);
        assertPreCommitCrash(temp.resolve("commit-before.db"), CrashInjector.Point.COMMIT_BEFORE);
    }

    @Test void crashAfterCommitLeavesExactlyOneCommittedSuperstep(@TempDir Path temp) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            store.create(RunState.initial(new RunSpec("commit-after", "", "commit-after", "", List.of(),
                    "commit", "default", 16, Map.of())));
            Activation activation = store.claim("commit-after", "owner", clock.instant(),
                    Duration.ofSeconds(30)).orElseThrow();
            FixedPhaseExecutor phases = completingPhases();
            CrashInjector crash = CrashInjector.failOnce(CrashInjector.Point.COMMIT_AFTER);
            SuperstepExecutor executor = new SuperstepExecutor(store, phases, new RuntimeReducer(),
                    new AtomicCommitter(store, clock, crash), crash);

            assertThrows(CrashInjector.InjectedCrash.class, () -> executor.execute(activation));
            RunState committed = store.get("commit-after").orElseThrow().state();
            assertEquals(RunStatus.READY, committed.status());
            assertEquals(RuntimePhase.CONTEXT, committed.phase());
            assertEquals(1, committed.commitSequence());
            assertEquals("durable", committed.channels().get("value"));
            assertEquals(committed, store.replay("commit-after", Long.MAX_VALUE).state());
            assertEquals(1, store.events("commit-after").stream()
                    .filter(event -> event.type().equals("SUPERSTEP_COMMITTED")).count());
        }
    }

    @Test void inboxConsumptionCrashRollsBackBothProjectionAndAcknowledgement(@TempDir Path temp) {
        Path database = temp.resolve("runtime.db");
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        CrashInjector crash = CrashInjector.failOnce(CrashInjector.Point.INBOX_CONSUMPTION);
        try (SqliteDurableRuntimeStore crashingStore = new SqliteDurableRuntimeStore(database, crash)) {
            crashingStore.create(RunState.initial(new RunSpec("inbox-crash", "", "inbox-crash", "", List.of(),
                    "inbox", "default", 16, Map.of())));
            crashingStore.enqueueEvent("inbox-crash", new ExternalEvent.SteeringMessage(
                    "steer-inbox", "inbox-crash", clock.instant(), Map.of("text", "durable")));
            Activation activation = crashingStore.claim("inbox-crash", "owner-a", clock.instant(),
                    Duration.ofSeconds(10)).orElseThrow();
            SuperstepExecutor executor = new SuperstepExecutor(crashingStore, new FixedPhaseExecutor(Map.of()),
                    new RuntimeReducer(), new AtomicCommitter(crashingStore, clock), CrashInjector.NONE);

            assertThrows(CrashInjector.InjectedCrash.class, () -> executor.execute(activation));
            assertEquals(RunStatus.RUNNING, crashingStore.get("inbox-crash").orElseThrow().state().status());
            assertEquals(1, crashingStore.pendingInbox("inbox-crash").size());
            assertFalse(crashingStore.get("inbox-crash").orElseThrow().state().channels()
                    .containsKey("lastExternalEvents"));
        }

        clock.advance(Duration.ofSeconds(11));
        try (SqliteDurableRuntimeStore recoveredStore = new SqliteDurableRuntimeStore(database)) {
            recoveredStore.recoverExpiredActivations(clock.instant());
            Activation activation = recoveredStore.claim("inbox-crash", "owner-b", clock.instant(),
                    Duration.ofSeconds(10)).orElseThrow();
            new SuperstepExecutor(recoveredStore, new FixedPhaseExecutor(Map.of()), new RuntimeReducer(),
                    new AtomicCommitter(recoveredStore, clock)).execute(activation);
            assertTrue(recoveredStore.pendingInbox("inbox-crash").isEmpty());
            assertEquals(1, ((List<?>) recoveredStore.get("inbox-crash").orElseThrow().state()
                    .channels().get("lastExternalEvents")).size());
        }
    }

    @Test void cancellationCrashOccursOnlyAfterTheRequestIsDurable(@TempDir Path temp) {
        CrashInjector crash = CrashInjector.failOnce(CrashInjector.Point.CANCELLATION_PERSISTED);
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"));
             LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store,
                     new FixedPhaseExecutor(Map.of(RuntimePhase.MODEL, context -> new PhaseResult(List.of(),
                             List.of(new RuntimeCommand.Suspend(new WaitReason.UserInputWait("cancel", "wait")))))),
                     null, Clock.systemUTC(), "cancel-crash", Duration.ofSeconds(30), crash)) {
            runtime.start(new RunSpec("cancel-crash", "", "cancel-crash", "", List.of(),
                    "cancel", "default", 16, Map.of()));
            ExternalEvent.CancelRequested cancel = new ExternalEvent.CancelRequested(
                    "cancel-durable", "cancel-crash", Instant.now(), Map.of());
            assertThrows(CrashInjector.InjectedCrash.class, () -> runtime.submit("cancel-crash", cancel));
            RunState state = runtime.get("cancel-crash").orElseThrow().state();
            assertEquals(RunStatus.CANCELLED, state.status());
            assertTrue(state.cancelRequested());
            assertEquals(1, runtime.events("cancel-crash").stream()
                    .filter(event -> event.type().equals("EXTERNAL_EVENT_ACCEPTED")).count());
        }
    }

    @Test void externalActionResultCompletesItsEffectLedgerEntry(@TempDir Path temp) {
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(temp.resolve("runtime.db"))) {
            store.create(RunState.initial(new RunSpec("external", "", "external", "", List.of(),
                    "external", "default", 16, Map.of())));
            commit(store, "external", List.of(ChannelWrite.set("pendingEffectId", "effect-external")),
                    List.of(new RuntimeCommand.Suspend(new WaitReason.ExternalEventWait("call-1",
                            "ExternalActionResult", Map.of("effectId", "effect-external")))));
            EffectIntent intent = new EffectIntent("effect-external", "external", "activation", "remote",
                    "digest", "external-key", List.of(), Map.of(), "external-result");
            store.saveEffect(new EffectRecord(intent, EffectRecord.Status.PREPARED, 1, -1,
                    Map.of(), "", "", Instant.now()));
            store.saveEffect(new EffectRecord(intent, EffectRecord.Status.DISPATCHING, 1, -1,
                    Map.of("request", true), "", "", Instant.now()));
            RunState current = store.get("external").orElseThrow().state();
            ExternalEvent event = new ExternalEvent.ExternalActionResult("external-result-1", "call-1",
                    Instant.now(), Map.of("effectId", "effect-external", "outcome", "SUCCEEDED",
                            "resultReference", "artifact:remote"));
            store.acceptEvent(ExternalEventBatch.single(ExternalEventCommit.reduce(
                    current, event, List.of(), new RuntimeReducer())));
            EffectRecord effect = store.effect("effect-external").orElseThrow();
            assertEquals(EffectRecord.Status.SUCCEEDED, effect.status());
            assertEquals("artifact:remote", effect.resultReference());
        }
    }

    @Test void archivesSchemaV2BeforeCreatingV3(@TempDir Path temp) throws Exception {
        Path db = temp.resolve(".ricbot/runtime.db");
        Files.createDirectories(db.getParent());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT, digest TEXT)");
            statement.execute("INSERT INTO schema_migrations VALUES(2,'2026-01-01T00:00:00Z','old')");
        }
        try (SqliteDurableRuntimeStore ignored = new SqliteDurableRuntimeStore(db)) {
            assertTrue(Files.exists(db));
        }
        try (var archives = Files.list(db.getParent().resolve("archive"))) {
            Path archived = archives.findFirst().orElseThrow();
            assertTrue(Files.exists(archived.resolve("runtime.db")));
            String manifest = Files.readString(archived.resolve("manifest.json"));
            assertTrue(manifest.contains("\"schemaVersion\" : 2"));
            assertTrue(manifest.contains(sha256(archived.resolve("runtime.db"))));
        }
    }

    @Test void refusesToArchiveActiveV5Lease(@TempDir Path temp) throws Exception {
        Path db = temp.resolve(".ricbot/runtime.db");
        Files.createDirectories(db.getParent());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT, digest TEXT)");
            statement.execute("INSERT INTO schema_migrations VALUES(2,'2026-01-01T00:00:00Z','old')");
            statement.execute("CREATE TABLE runtime_instances(instance_id TEXT,status TEXT,expires_at TEXT)");
            statement.execute("INSERT INTO runtime_instances VALUES('live','ACTIVE','2999-01-01T00:00:00Z')");
        }
        assertThrows(IllegalStateException.class, () -> new SqliteDurableRuntimeStore(db));
        assertTrue(Files.exists(db));
        assertFalse(Files.exists(db.getParent().resolve("archive")));
    }

    @Test void refusesUnknownOrCorruptSchemaWithoutMovingSource(@TempDir Path temp) throws Exception {
        Path unknown = temp.resolve("unknown.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + unknown);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE runtime_schema(version INTEGER PRIMARY KEY, graph_version TEXT, created_at TEXT)");
            statement.execute("INSERT INTO runtime_schema VALUES(99,'future','2026-01-01T00:00:00Z')");
        }
        String unknownDigest = sha256(unknown);
        assertThrows(IllegalStateException.class, () -> new SqliteDurableRuntimeStore(unknown));
        assertEquals(unknownDigest, sha256(unknown));

        Path corrupt = temp.resolve("corrupt.db");
        Files.writeString(corrupt, "not sqlite", StandardCharsets.UTF_8);
        String corruptDigest = sha256(corrupt);
        assertThrows(IllegalStateException.class, () -> new SqliteDurableRuntimeStore(corrupt));
        assertEquals(corruptDigest, sha256(corrupt));
    }

    @Test void rebuildsOnlyTheExactKnownInactiveLegacyV3Layout(@TempDir Path temp) throws Exception {
        Path known = temp.resolve("known-v3.db");
        createLegacyV3(known, false, false);
        try (SqliteDurableRuntimeStore ignored = new SqliteDurableRuntimeStore(known)) {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + known);
                 var result = connection.createStatement().executeQuery(
                         "SELECT layout_fingerprint FROM runtime_schema WHERE version=3")) {
                assertTrue(result.next());
                assertFalse(result.getString(1).isBlank());
            }
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + known);
                 var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM runs")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "the explicitly accepted legacy policy rebuilds an empty database");
            }
        }

        Path active = temp.resolve("active-v3.db");
        createLegacyV3(active, true, false);
        String activeDigest = sha256(active);
        assertThrows(IllegalStateException.class, () -> new SqliteDurableRuntimeStore(active));
        assertEquals(activeDigest, sha256(active));

        Path unknown = temp.resolve("unknown-v3.db");
        createLegacyV3(unknown, false, true);
        String unknownDigest = sha256(unknown);
        assertThrows(IllegalStateException.class, () -> new SqliteDurableRuntimeStore(unknown));
        assertEquals(unknownDigest, sha256(unknown));
    }

    @Test void oneAtomicSpawnUsesOneGlobalTransactionSequence(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("transaction.db");
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(database)) {
            store.create(RunState.initial(new RunSpec("tx-root", "", "tx-root", "", List.of(),
                    "root", "default", 16, Map.of())));
            commit(store, "tx-root", List.of(), List.of(new RuntimeCommand.SpawnChildRuns(List.of(
                    new RunSpec("tx-child", "tx-root", "tx-root", "", List.of(),
                            "child", "default", 16, Map.of())), true)));
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT transaction_sequence FROM commits WHERE run_id='tx-root' AND commit_sequence=1
                     UNION SELECT transaction_sequence FROM run_origins WHERE run_id='tx-child'
                     UNION SELECT transaction_sequence FROM run_relations WHERE child_run_id='tx-child'
                     UNION SELECT transaction_sequence FROM runtime_events WHERE run_id='tx-child'
                     """)) {
            List<Long> sequences = new ArrayList<>();
            while (result.next()) sequences.add(result.getLong(1));
            assertEquals(1, sequences.size(), sequences.toString());
        }
    }

    @Test void applicationSchemaUpgradeDropsOnlyTheLegacySideEffectTable(@TempDir Path temp)
            throws Exception {
        Path application = temp.resolve(".ricbot/application.db");
        Files.createDirectories(application.getParent());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + application);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE side_effects(id TEXT PRIMARY KEY, value TEXT)");
            statement.execute("INSERT INTO side_effects VALUES('legacy','discard')");
            statement.execute("CREATE TABLE retained_application_data(id TEXT PRIMARY KEY, value TEXT)");
            statement.execute("INSERT INTO retained_application_data VALUES('keep','value')");
        }
        try (SqliteRuntimeStore ignored = new SqliteRuntimeStore(temp)) {
            // Opening the application store performs the schema upgrade transaction.
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + application);
             var statement = connection.createStatement()) {
            try (var removed = statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='side_effects'")) {
                assertTrue(removed.next());
                assertEquals(0, removed.getInt(1));
            }
            try (var retained = statement.executeQuery(
                    "SELECT value FROM retained_application_data WHERE id='keep'")) {
                assertTrue(retained.next());
                assertEquals("value", retained.getString(1));
            }
            try (var version = statement.executeQuery("SELECT MAX(version) FROM application_schema")) {
                assertTrue(version.next());
                assertEquals(2, version.getInt(1));
            }
        }
    }

    private static RunState running(RunState value) {
        return new RunState(RunState.SCHEMA_VERSION, RunState.GRAPH_VERSION, value.spec(), RunStatus.RUNNING,
                value.phase(), value.superstep(), value.commitSequence(), value.channels(), null,
                value.cancelRequested(), value.failureCode(), value.failureMessage(), value.childRunIds(),
                value.artifactReferences());
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long started = System.nanoTime();
        while (!condition.getAsBoolean() && System.nanoTime() - started < timeout.toNanos()) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met within " + timeout);
    }

    private static FixedPhaseExecutor completingPhases() {
        return new FixedPhaseExecutor(Map.of(RuntimePhase.INGEST, context -> new PhaseResult(
                List.of(ChannelWrite.set("value", "durable")),
                List.of(new RuntimeCommand.Transition(RuntimePhase.CONTEXT)))));
    }

    private static void assertPreCommitCrash(Path database, CrashInjector.Point point) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(database)) {
            store.create(RunState.initial(new RunSpec("precommit-" + point.name(), "",
                    "precommit-" + point.name(), "", List.of(), "commit", "default", 16, Map.of())));
            String runId = store.list().get(0).state().spec().runId();
            Activation activation = store.claim(runId, "owner-a", clock.instant(),
                    Duration.ofSeconds(10)).orElseThrow();
            CrashInjector crash = CrashInjector.failOnce(point);
            SuperstepExecutor executor = new SuperstepExecutor(store, completingPhases(), new RuntimeReducer(),
                    new AtomicCommitter(store, clock, crash), crash);
            assertThrows(CrashInjector.InjectedCrash.class, () -> executor.execute(activation));
            RunState interrupted = store.get(runId).orElseThrow().state();
            assertEquals(RunStatus.RUNNING, interrupted.status());
            assertEquals(0, interrupted.commitSequence());
            assertFalse(interrupted.channels().containsKey("value"));

            clock.advance(Duration.ofSeconds(11));
            store.recoverExpiredActivations(clock.instant());
            Activation recovered = store.claim(runId, "owner-b", clock.instant(),
                    Duration.ofSeconds(10)).orElseThrow();
            new SuperstepExecutor(store, completingPhases(), new RuntimeReducer(),
                    new AtomicCommitter(store, clock)).execute(recovered);
            RunState committed = store.get(runId).orElseThrow().state();
            assertEquals(1, committed.commitSequence());
            assertEquals("durable", committed.channels().get("value"));
            assertEquals(committed, store.replay(runId, Long.MAX_VALUE).state());
        }
    }

    private static RunState commit(SqliteDurableRuntimeStore store, String runId,
                                   List<ChannelWrite> writes, List<RuntimeCommand> commands) {
        Instant now = Instant.now();
        Activation activation = store.claim(runId, "test-owner", now, Duration.ofMinutes(1)).orElseThrow();
        RunState running = store.get(runId).orElseThrow().state();
        Reduction reduction = new RuntimeReducer().reduce(running, writes, commands);
        List<String> consumedEventIds = store.pendingInbox(runId).stream()
                .map(ExternalEvent::eventId).toList();
        return store.commit(new CommitBatch(activation, running, reduction, writes, commands, consumedEventIds,
                "TEST_COMMIT", Map.of(), now.plusMillis(1)));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void createLegacyV3(Path database, boolean activeLease, boolean extraTable)
            throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE runtime_schema(version INTEGER PRIMARY KEY, graph_version TEXT, created_at TEXT)");
            statement.execute("INSERT INTO runtime_schema VALUES(3,'ricbot-durable-runtime-v6','2026-01-01T00:00:00Z')");
            statement.execute("CREATE TABLE runs(run_id TEXT)");
            statement.execute("CREATE TABLE run_origins(run_id TEXT)");
            statement.execute("CREATE TABLE activations(status TEXT, lease_expires_at TEXT)");
            statement.execute("CREATE TABLE inbox(event_id TEXT)");
            statement.execute("CREATE TABLE commits(run_id TEXT)");
            statement.execute("CREATE TABLE channel_writes(run_id TEXT)");
            statement.execute("CREATE TABLE model_invocations(invocation_id TEXT)");
            statement.execute("CREATE TABLE effects(effect_id TEXT)");
            statement.execute("CREATE TABLE resource_leases(expires_at TEXT)");
            statement.execute("CREATE TABLE run_relations(parent_run_id TEXT)");
            statement.execute("CREATE TABLE runtime_events(event_id TEXT)");
            statement.execute("INSERT INTO runs VALUES('historical-run')");
            statement.execute("INSERT INTO effects VALUES('unknown-effect')");
            if (activeLease) statement.execute(
                    "INSERT INTO activations VALUES('RUNNING','2999-01-01T00:00:00Z')");
            if (extraTable) statement.execute("CREATE TABLE unexpected_runtime_state(value TEXT)");
        }
    }

    private static long transactionCount(Path database) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM runtime_transactions")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private static ModelInvocation prepared(String invocationId, String runId, long reservedTokens) {
        return new ModelInvocation(invocationId, runId, "activation", 1, ModelInvocation.Status.PREPARED,
                "request-digest", reservedTokens, ModelInvocation.UnknownPolicy.RECONCILE_THEN_WAIT,
                "provider:" + invocationId, "", "", Map.of(), false, false, 0, "", Instant.now());
    }

    private static EffectIntent effectIntent(String effectId, String runId) {
        return new EffectIntent(effectId, runId, "activation", "tool", "digest",
                "key:" + effectId, List.of(), Map.of(), "manual");
    }

    private record EventCase(String name, WaitReason waitReason, ExternalEvent event,
                             List<RuntimeCommand> commands) { }

    private static boolean reserve(SqliteDurableRuntimeStore store, ModelInvocation invocation,
                                   CountDownLatch start) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            store.saveModelInvocation(invocation);
            return true;
        } catch (IllegalStateException rejected) {
            assertTrue(rejected.getMessage().contains("budget reservation rejected"));
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static boolean reserveTool(SqliteDurableRuntimeStore store, String runId,
                                       String reservationId, CountDownLatch start) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            store.reserveToolCall(runId, reservationId, Instant.now());
            return true;
        } catch (IllegalStateException rejected) {
            assertTrue(rejected.getMessage().contains("budget reservation rejected"));
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static boolean reserveActive(SqliteDurableRuntimeStore store, String runId, String reservationId) {
        try {
            store.reserveActiveTime(runId, reservationId, 1_000L, Instant.now());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private MutableClock(Instant instant) { this.instant = new AtomicReference<>(instant); }
        void advance(Duration duration) { instant.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }

    private static final class FailingTranscriptPort implements TranscriptPort {
        private final int failAtCopy;
        private int copies;
        private final Map<String, List<Map<String, Object>>> values = new LinkedHashMap<>();
        private final Set<String> cleaned = new java.util.LinkedHashSet<>();

        private FailingTranscriptPort(int failAtCopy) { this.failAtCopy = failAtCopy; }
        @Override public String initialize(String runId, List<Map<String, Object>> messages) {
            values.put(runId, new ArrayList<>(messages)); return reference(runId);
        }
        @Override public void append(String runId, String entryId, Map<String, Object> message) {
            values.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(Map.copyOf(message));
        }
        @Override public List<Map<String, Object>> read(String runId) {
            return List.copyOf(values.getOrDefault(runId, List.of()));
        }
        @Override public long size(String runId) { return read(runId).size(); }
        @Override public String reference(String runId) { return "memory:" + runId; }
        @Override public String copy(String sourceRunId, String targetRunId, long throughCursor) {
            if (++copies == failAtCopy) throw new IllegalStateException("injected transcript copy failure");
            return TranscriptPort.super.copy(sourceRunId, targetRunId, throughCursor);
        }
        @Override public void cleanup(String runId) { cleaned.add(runId); values.remove(runId); }
        boolean contains(String runId) { return values.containsKey(runId); }
        Set<String> cleaned() { return Set.copyOf(cleaned); }
    }
}
