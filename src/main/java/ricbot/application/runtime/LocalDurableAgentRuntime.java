package ricbot.application.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.domain.runtime.*;
import ricbot.domain.runtime.dto.RuntimeDigest;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local process driver for the durable v6 kernel. */
public final class LocalDurableAgentRuntime implements DurableAgentRuntime, RunQuery {
    private static final Logger LOG = LoggerFactory.getLogger(LocalDurableAgentRuntime.class);
    private static final long MIN_POLL_MILLIS = 100L;
    private static final long MAX_POLL_MILLIS = 5_000L;
    private static final int SCHEDULE_BATCH_LIMIT = 64;
    private final DurableRuntimeStore store;
    private final RuntimeReducer reducer;
    private final ActivationScheduler scheduler;
    private final SuperstepExecutor supersteps;
    private final Clock clock;
    private final PhaseExecutor phases;
    private final TranscriptPort transcripts;
    private final CrashInjector crashes;
    private final ScheduledExecutorService workers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean tickRunning = new AtomicBoolean();
    private final java.util.concurrent.ConcurrentHashMap<String, Object> runLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> backgroundFuture;
    private volatile java.time.Instant lastSuccessfulTick;
    private volatile java.time.Instant lastFailedTick;
    private volatile long consecutiveFailures;
    private volatile String lastFailure = "";
    private volatile long currentPollDelayMillis = MIN_POLL_MILLIS;
    private volatile int lastBatchSize;

    public LocalDurableAgentRuntime(DurableRuntimeStore store, PhaseExecutor phases) {
        this(store, phases, null, Clock.systemUTC(), "runtime-" + UUID.randomUUID(), Duration.ofSeconds(30));
    }

    public LocalDurableAgentRuntime(DurableRuntimeStore store, PhaseExecutor phases, Clock clock,
                                    String owner, Duration leaseDuration) {
        this(store, phases, null, clock, owner, leaseDuration);
    }

    public LocalDurableAgentRuntime(DurableRuntimeStore store, PhaseExecutor phases, TranscriptPort transcripts,
                                    Clock clock, String owner, Duration leaseDuration) {
        this(store, phases, transcripts, clock, owner, leaseDuration, CrashInjector.NONE);
    }

    public LocalDurableAgentRuntime(DurableRuntimeStore store, PhaseExecutor phases, TranscriptPort transcripts,
                                    Clock clock, String owner, Duration leaseDuration, CrashInjector crashes) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.phases = Objects.requireNonNull(phases, "phases");
        this.transcripts = transcripts;
        this.crashes = crashes != null ? crashes : CrashInjector.NONE;
        this.reducer = new RuntimeReducer();
        this.scheduler = new ActivationScheduler(store, owner, clock, leaseDuration, this.crashes);
        this.supersteps = new SuperstepExecutor(store, this.phases, reducer,
                new AtomicCommitter(store, clock, this.crashes), this.crashes);
        this.workers = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "durable-runtime-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduleNext(MIN_POLL_MILLIS);
    }

    @Override public RunView start(RunSpec spec) {
        RunState initial = RunState.initial(spec);
        if (transcripts != null) {
            Map<String, Object> channels = merge(initial.channels(), Map.of(
                    "transcriptReference", transcripts.reference(spec.runId()),
                    "transcriptCursor", transcripts.size(spec.runId())));
            initial = new RunState(initial.schemaVersion(), initial.graphVersion(), initial.spec(),
                    initial.status(), initial.phase(), initial.superstep(), initial.commitSequence(),
                    channels, initial.waitReason(), initial.cancelRequested(), initial.failureCode(),
                    initial.failureMessage(), initial.childRunIds(), initial.artifactReferences());
        }
        store.create(initial);
        RunView result = drive(spec.runId());
        wakeScheduler();
        return result;
    }

    @Override public RunView submit(String runId, ExternalEvent event) {
        wakeScheduler();
        Object lock = runLocks.computeIfAbsent(runId, ignored -> new Object());
        RunState snapshot = require(runId);
        validateEvent(snapshot, event);
        if (snapshot.status() == RunStatus.RUNNING) {
            store.enqueueEvent(runId, event);
            afterEventPersisted(runId, event);
            synchronized (lock) {
                RunState settled = require(runId);
                return settled.status() == RunStatus.READY ? driveLocked(runId) : view(settled);
            }
        }
        synchronized (lock) {
            return submitAtRest(runId, event);
        }
    }

    private RunView submitAtRest(String runId, ExternalEvent event) {
        RunState current = require(runId);
        validateEvent(current, event);
        List<RuntimeCommand> commands = new ArrayList<>();
        List<EffectRecord> unresolved = unresolvedForCancellation(runId, event);
        if ((event instanceof ExternalEvent.CancelRequested || current.cancelRequested()) && !unresolved.isEmpty()) {
            List<String> effectIds = unresolved.stream().map(value -> value.intent().effectId()).sorted().toList();
            commands.add(new RuntimeCommand.CancelAtBoundary(event.correlationId(), effectIds));
        } else if (event instanceof ExternalEvent.CancelRequested || current.cancelRequested()) {
            commands.add(new RuntimeCommand.CancelAtBoundary(event.correlationId(), List.of()));
        }
        ExternalEventCommit commit = ExternalEventCommit.reduce(current, event, commands, reducer);
        RunState stored = store.acceptEvent(ExternalEventBatch.single(commit));
        afterEventPersisted(runId, event);
        return stored.status() == RunStatus.READY ? driveLocked(runId) : view(stored);
    }

    private void afterEventPersisted(String runId, ExternalEvent event) {
        if (event instanceof ExternalEvent.CancelRequested) {
            crashes.at(CrashInjector.Point.CANCELLATION_PERSISTED,
                    Map.of("runId", runId, "eventId", event.eventId()));
            if (phases instanceof CancellationPort cancellation) cancellation.cancel(runId);
        }
    }

    private List<EffectRecord> unresolvedForCancellation(String runId, ExternalEvent event) {
        List<EffectRecord> unresolved = new ArrayList<>();
        String confirmedId = event instanceof ExternalEvent.EffectConfirmation
                ? String.valueOf(event.payload().getOrDefault("effectId", "")) : "";
        for (EffectRecord record : store.unresolvedEffects(runId)) {
            if (record.intent().effectId().equals(confirmedId)) continue;
            if (record.status() == EffectRecord.Status.DISPATCHING) {
                record = store.saveEffect(new EffectRecord(record.intent(), EffectRecord.Status.UNKNOWN,
                        record.attempt(), record.committedSuperstep(), record.executionEvidence(),
                        record.resultReference(), "cancellation interrupted dispatch before observation", clock.instant()));
            }
            unresolved.add(record);
        }
        return List.copyOf(unresolved);
    }

    @Override public RunView fork(ForkSpec spec) {
        ForkSnapshot snapshot = store.forkSnapshot(spec.sourceRunId(), spec.throughCommit());
        RunState source = snapshot.root();
        ensureForkableTree(source, snapshot);
        ForkTree tree = buildForkTree(source, spec.newRunId(), snapshot);
        RunSpec forkSpec = new RunSpec(spec.newRunId(), "", spec.newRunId(), "",
                forkDependencies(source, snapshot, tree.idMap()),
                source.spec().goal(), source.spec().executionPolicyRef(), source.spec().maxSupersteps(),
                merge(source.spec().metadata(), Map.of("forkedFromRunId", spec.sourceRunId(),
                        "forkSourceSuperstep", source.superstep(), "throughCommit", spec.throughCommit())));
        List<RunState> descendants = new ArrayList<>();
        for (ForkNode node : tree.nodes()) {
            RunState child = node.source();
            RunSpec childSpec = new RunSpec(node.targetRunId(), node.targetParentRunId(), spec.newRunId(),
                    rewriteId(child.spec().retryOfRunId(), tree.idMap()),
                    forkDependencies(child, snapshot, tree.idMap()), child.spec().goal(),
                    child.spec().executionPolicyRef(), child.spec().maxSupersteps(),
                    merge(child.spec().metadata(), Map.of("forkedFromRunId", child.spec().runId(),
                            "forkSourceSuperstep", child.superstep())));
            descendants.add(forkState(child, childSpec, tree.childIds(node.targetRunId()),
                    mergedFacts(tree.facts(node.targetRunId()), dependencyFacts(child, snapshot)),
                    spec.execute(), spec.newRunId()));
        }
        RunState forked = forkState(source, forkSpec, tree.childIds(spec.newRunId()),
                mergedFacts(tree.facts(spec.newRunId()), dependencyFacts(source, snapshot)),
                spec.execute(), spec.newRunId());
        List<String> copiedTranscripts = copyForkTranscripts(source, forked, tree, descendants);
        try {
            store.fork(spec, forked, descendants);
        } catch (RuntimeException failure) {
            if (transcripts != null) copiedTranscripts.forEach(transcripts::cleanup);
            throw failure;
        }
        RunView result = spec.execute() ? drive(spec.newRunId()) : view(forked);
        wakeScheduler();
        return result;
    }

    @Override public StateReplay replayState(String runId, long throughCommit) {
        return store.replay(runId, throughCommit);
    }

    @Override public AutoCloseable subscribe(RuntimeEventSubscriber subscriber) { return store.subscribe(subscriber); }
    @Override public List<RunView> list() { return store.list(); }
    @Override public java.util.Optional<RunView> get(String runId) { return store.get(runId); }
    @Override public List<RunView> children(String runId) { return store.children(runId); }
    @Override public List<RuntimeEvent> events(String runId) { return store.events(runId); }
    @Override public List<TimelineEvent> timeline(String runId) { return store.timeline(runId); }
    @Override public RuntimeHealth health() {
        return new RuntimeHealth(!closed.get(), lastSuccessfulTick, lastFailedTick,
                consecutiveFailures, lastFailure, currentPollDelayMillis, lastBatchSize);
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ScheduledFuture<?> scheduled = backgroundFuture;
        if (scheduled != null) scheduled.cancel(false);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("durable runtime scheduler did not stop");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping durable runtime scheduler", failure);
        }
        store.close();
    }

    private RunView drive(String runId) {
        synchronized (runLocks.computeIfAbsent(runId, ignored -> new Object())) {
            try {
                return driveLocked(runId);
            } finally {
                RunState state = require(runId);
                if (state.status().terminal()) runLocks.remove(runId);
            }
        }
    }

    private RunView driveLocked(String runId) {
        while (true) {
            scheduler.tick();
            RunState state = require(runId);
            if (state.status() == RunStatus.WAITING || state.status().terminal()) return view(state);
            if (state.superstep() >= state.spec().maxSupersteps()) {
                ExternalEvent event = new ExternalEvent.RuntimeLimitReached(
                        "limit:" + runId + ":" + state.commitSequence(), runId, clock.instant(),
                        Map.of("limit", state.spec().maxSupersteps(), "actual", state.superstep()));
                RunState failed = store.acceptEvent(ExternalEventBatch.single(ExternalEventCommit.reduce(
                        state, event, List.of(new RuntimeCommand.Fail("MAX_SUPERSTEPS_EXCEEDED",
                                "run reached maxSupersteps=" + state.spec().maxSupersteps())), reducer)));
                return view(failed);
            }
            Activation activation = scheduler.claim(runId).orElse(null);
            if (activation == null) return view(require(runId));
            long renewalMillis = Math.max(10L, scheduler.leaseDuration().toMillis() / 3L);
            ScheduledFuture<?> renewal = workers.scheduleAtFixedRate(() -> scheduler.renew(activation),
                    renewalMillis, renewalMillis, TimeUnit.MILLISECONDS);
            try { supersteps.execute(activation); }
            finally { renewal.cancel(false); }
        }
    }

    /** Polls timers, expired leases and ready Runs; useful for deterministic hosts and tests. */
    public void runScheduledWork() {
        runScheduledBatch();
    }

    private int runScheduledBatch() {
        scheduler.tick();
        List<RunView> candidates = store.readyRuns(SCHEDULE_BATCH_LIMIT);
        for (RunView candidate : candidates) {
            drive(candidate.state().spec().runId());
        }
        return candidates.size();
    }

    private void backgroundTick() {
        if (closed.get()) return;
        if (!tickRunning.compareAndSet(false, true)) return;
        try {
            int processed = runScheduledBatch();
            lastBatchSize = processed;
            lastSuccessfulTick = clock.instant();
            consecutiveFailures = 0;
            lastFailure = "";
            currentPollDelayMillis = processed > 0 ? MIN_POLL_MILLIS
                    : Math.min(MAX_POLL_MILLIS, Math.max(MIN_POLL_MILLIS,
                    currentPollDelayMillis * 2L));
        } catch (RuntimeException failure) {
            lastFailedTick = clock.instant();
            consecutiveFailures++;
            lastFailure = failure.getClass().getSimpleName() + ": "
                    + (failure.getMessage() != null ? failure.getMessage() : "no message");
            currentPollDelayMillis = Math.min(MAX_POLL_MILLIS,
                    Math.max(MIN_POLL_MILLIS, currentPollDelayMillis * 2L));
            LOG.error("durable runtime background tick failed; consecutiveFailures={}",
                    consecutiveFailures, failure);
        } finally {
            tickRunning.set(false);
            scheduleNext(currentPollDelayMillis);
        }
    }

    private synchronized void scheduleNext(long delayMillis) {
        if (closed.get()) return;
        currentPollDelayMillis = Math.max(0L, Math.min(MAX_POLL_MILLIS, delayMillis));
        backgroundFuture = workers.schedule(this::backgroundTick, currentPollDelayMillis,
                TimeUnit.MILLISECONDS);
    }

    private synchronized void wakeScheduler() {
        if (closed.get() || currentPollDelayMillis <= MIN_POLL_MILLIS) return;
        ScheduledFuture<?> scheduled = backgroundFuture;
        if (scheduled != null) scheduled.cancel(false);
        scheduleNext(0L);
    }

    private static void validateEvent(RunState state, ExternalEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof ExternalEvent.RuntimeLimitReached) {
            throw new IllegalArgumentException("RuntimeLimitReached is an internal event");
        }
        if (state.status().terminal()) throw new IllegalStateException("terminal runs cannot accept events");
        if (event instanceof ExternalEvent.CancelRequested || event instanceof ExternalEvent.SteeringMessage) return;
        if (state.status() != RunStatus.WAITING) {
            if (event instanceof ExternalEvent.UserMessage) return;
            throw new IllegalStateException("run is not waiting for " + event.getClass().getSimpleName());
        }
        WaitReason wait = state.waitReason();
        boolean matches;
        if (wait instanceof WaitReason.ApprovalWait approval) {
            matches = event instanceof ExternalEvent.ApprovalDecision
                    && (approval.correlationId().equals(event.correlationId())
                    || approval.approvalRequestId().equals(event.correlationId())
                    || approval.approvalRequestId().equals(String.valueOf(event.payload().getOrDefault("requestId", ""))));
        } else if (wait instanceof WaitReason.UserInputWait input) {
            matches = event instanceof ExternalEvent.UserMessage && input.correlationId().equals(event.correlationId());
        } else if (wait instanceof WaitReason.ChildRunWait children) {
            matches = event instanceof ExternalEvent.ChildRunCompleted child
                    && children.childRunIds().contains(String.valueOf(child.payload().getOrDefault(
                    "childRunId", child.correlationId())));
        } else if (wait instanceof WaitReason.RetryWait retry) {
            matches = event instanceof ExternalEvent.TimerExpired && retry.correlationId().equals(event.correlationId());
        } else if (wait instanceof WaitReason.ExternalEventWait external) {
            matches = external.correlationId().equals(event.correlationId())
                    && external.eventType().equals(event.getClass().getSimpleName());
        } else {
            matches = false;
        }
        if (!matches) throw new IllegalArgumentException("event does not match wait reason: " + wait);
    }

    private void ensureForkableTree(RunState state, ForkSnapshot snapshot) {
        if (state.waitReason() instanceof WaitReason.ExternalEventWait wait
                && "EffectConfirmation".equals(wait.eventType())) {
            throw new IllegalStateException("cannot fork a run with unresolved UNKNOWN effects: " + state.spec().runId());
        }
        for (RunState child : sourceChildren(state, snapshot)) ensureForkableTree(child, snapshot);
    }

    private ForkTree buildForkTree(RunState source, String targetRoot, ForkSnapshot snapshot) {
        List<ForkNode> nodes = new ArrayList<>();
        Map<String, String> idMap = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> facts = new LinkedHashMap<>();
        collectForkTree(source, targetRoot, snapshot, nodes, idMap, facts);
        return new ForkTree(List.copyOf(nodes), Map.copyOf(idMap), immutableLists(facts));
    }

    private void collectForkTree(RunState sourceParent, String targetParent, ForkSnapshot snapshot,
                                 List<ForkNode> nodes,
                                 Map<String, String> idMap,
                                 Map<String, List<Map<String, Object>>> facts) {
        for (RunState child : sourceChildren(sourceParent, snapshot)) {
            if (child.status().terminal()) {
                facts.computeIfAbsent(targetParent, ignored -> new ArrayList<>()).add(completedFact(child));
                collectForkTree(child, targetParent, snapshot, nodes, idMap, facts);
            } else {
                String targetId = "run-" + UUID.nameUUIDFromBytes((targetParent + "\n"
                        + child.spec().runId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                idMap.put(child.spec().runId(), targetId);
                nodes.add(new ForkNode(child, targetId, targetParent));
                collectForkTree(child, targetId, snapshot, nodes, idMap, facts);
            }
        }
    }

    private List<RunState> sourceChildren(RunState source, ForkSnapshot snapshot) {
        List<RunState> children = new ArrayList<>();
        for (String childId : source.childRunIds()) {
            children.add(snapshot.require(childId));
        }
        return List.copyOf(children);
    }

    private RunState forkState(RunState source, RunSpec spec, List<String> children,
                               List<Map<String, Object>> completedFacts, boolean execute, String forkRootId) {
        RunStatus status = execute ? RunStatus.READY : RunStatus.WAITING;
        RuntimePhase phase = execute ? RuntimePhase.INGEST : RuntimePhase.WAIT;
        WaitReason reason = execute ? null : new WaitReason.ApprovalWait(
                "fork:" + forkRootId, "execute-fork:" + forkRootId);
        Map<String, Object> channels = merge(source.channels(), Map.of("forkSource", source.spec().runId(),
                "forkSourceCommit", source.commitSequence()));
        if (!completedFacts.isEmpty()) channels = merge(channels, Map.of("completedChildFacts", completedFacts));
        if (transcripts != null) channels = merge(channels, Map.of(
                "transcriptReference", transcripts.reference(spec.runId()),
                "transcriptCursor", transcriptCursor(source)));
        return new RunState(RunState.SCHEMA_VERSION, RunState.GRAPH_VERSION, spec, status, phase, 0, 0,
                channels, reason, false, "", "", children, source.artifactReferences());
    }

    private List<String> copyForkTranscripts(RunState source, RunState forked, ForkTree tree,
                                             List<RunState> descendants) {
        if (transcripts == null) return List.of();
        List<String> created = new ArrayList<>();
        try {
            copyForkTranscript(source.spec().runId(), forked.spec().runId(), transcriptCursor(source), created);
            Map<String, RunState> targets = new LinkedHashMap<>();
            descendants.forEach(state -> targets.put(state.spec().runId(), state));
            for (ForkNode node : tree.nodes()) {
                RunState target = targets.get(node.targetRunId());
                if (target != null) copyForkTranscript(node.source().spec().runId(), target.spec().runId(),
                        transcriptCursor(node.source()), created);
            }
            return List.copyOf(created);
        } catch (RuntimeException failure) {
            for (String runId : created) {
                try { transcripts.cleanup(runId); }
                catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }
    }

    private void copyForkTranscript(String sourceRunId, String targetRunId, long cursor,
                                    List<String> created) {
        boolean empty = transcripts.size(targetRunId) == 0L;
        transcripts.copy(sourceRunId, targetRunId, cursor);
        if (empty) created.add(targetRunId);
    }

    private static long transcriptCursor(RunState state) {
        Object value = state.channels().get("transcriptCursor");
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static Map<String, Object> completedFact(RunState child) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("sourceRunId", child.spec().runId());
        fact.put("factReference", "run:" + child.spec().runId() + "@commit:" + child.commitSequence());
        fact.put("status", child.status().name());
        fact.put("projectionDigest", RuntimeDigest.sha256(child));
        fact.put("artifactReferences", child.artifactReferences());
        return Map.copyOf(fact);
    }

    private static String rewriteId(String id, Map<String, String> idMap) {
        return id != null && !id.isBlank() ? idMap.getOrDefault(id, id) : "";
    }

    private static List<String> forkDependencies(RunState source, ForkSnapshot snapshot,
                                                 Map<String, String> idMap) {
        List<String> dependencies = new ArrayList<>();
        for (String dependencyId : source.spec().dependencies()) {
            RunState dependency = snapshot.descendants().get(dependencyId);
            if (dependency != null && dependency.status().terminal()) continue;
            String rewritten = idMap.get(dependencyId);
            if (rewritten == null) {
                throw new IllegalStateException("cannot fork a dependency outside the historical Run tree: "
                        + dependencyId);
            }
            dependencies.add(rewritten);
        }
        return List.copyOf(dependencies);
    }

    private static List<Map<String, Object>> dependencyFacts(RunState source, ForkSnapshot snapshot) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (String dependencyId : source.spec().dependencies()) {
            RunState dependency = snapshot.descendants().get(dependencyId);
            if (dependency != null && dependency.status().terminal()) facts.add(completedFact(dependency));
        }
        return List.copyOf(facts);
    }

    private static List<Map<String, Object>> mergedFacts(List<Map<String, Object>> left,
                                                         List<Map<String, Object>> right) {
        Map<String, Map<String, Object>> facts = new LinkedHashMap<>();
        for (Map<String, Object> fact : left) facts.put(String.valueOf(fact.get("factReference")), fact);
        for (Map<String, Object> fact : right) facts.put(String.valueOf(fact.get("factReference")), fact);
        return List.copyOf(facts.values());
    }

    private static Map<String, List<Map<String, Object>>> immutableLists(
            Map<String, List<Map<String, Object>>> values) {
        Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private record ForkNode(RunState source, String targetRunId, String targetParentRunId) { }
    private record ForkTree(List<ForkNode> nodes, Map<String, String> idMap,
                            Map<String, List<Map<String, Object>>> completedFacts) {
        List<String> childIds(String parentId) {
            return nodes.stream().filter(node -> node.targetParentRunId().equals(parentId))
                    .map(ForkNode::targetRunId).toList();
        }
        List<Map<String, Object>> facts(String parentId) {
            return completedFacts.getOrDefault(parentId, List.of());
        }
    }

    private RunState require(String runId) {
        return store.get(runId).map(RunView::state).orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }
    private static RunView view(RunState state) { return new RunView(state, state.commitSequence(), RuntimeDigest.sha256(state)); }
    private static Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>(left); merged.putAll(right); return Map.copyOf(merged);
    }
}
