package ricbot.infra.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.domain.runtime.*;
import ricbot.domain.runtime.dto.RuntimeDigest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** SQLite schema-v3 adapter for the durable runtime. */
public final class SqliteDurableRuntimeStore implements DurableRuntimeStore {
    private static final Logger LOG = LoggerFactory.getLogger(SqliteDurableRuntimeStore.class);
    public static final int SCHEMA_VERSION = 3;
    private static final String LAYOUT_FINGERPRINT = RuntimeDigest.sha256(
            "ricbot-runtime-v3:transactions+budget+replayable-events:2026-08-31");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final Path database;
    private final SqliteRuntimeTransactionFacade transactions;
    private final CrashInjector crashes;
    private final SqliteRuntimeBudgetLedger budgets = new SqliteRuntimeBudgetLedger();
    private final SqliteRuntimeModelLedger models = new SqliteRuntimeModelLedger();
    private final SqliteRuntimeEffectResourceLedger effects = new SqliteRuntimeEffectResourceLedger();
    private final SqliteRuntimeJournal journal = new SqliteRuntimeJournal();
    private final SqliteRuntimeForkQueries forkQueries = new SqliteRuntimeForkQueries();
    private final CopyOnWriteArrayList<RuntimeEventSubscriber> subscribers = new CopyOnWriteArrayList<>();

    public SqliteDurableRuntimeStore(Path database) {
        this(database, CrashInjector.NONE);
    }

    public SqliteDurableRuntimeStore(Path database, CrashInjector crashes) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
        this.crashes = crashes != null ? crashes : CrashInjector.NONE;
        try {
            if (this.database.getParent() != null) Files.createDirectories(this.database.getParent());
            archiveV5IfNeeded(this.database);
            rebuildKnownLegacyV3IfNeeded(this.database);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot prepare runtime database", failure);
        }
        this.transactions = new SqliteRuntimeTransactionFacade("jdbc:sqlite:" + this.database);
        initialize();
    }

    public Path database() { return database; }

    @Override public RunState create(RunState initial) {
        Objects.requireNonNull(initial, "initial");
        if (initial.commitSequence() != 0 || initial.superstep() != 0 || initial.status() != RunStatus.READY) {
            throw new IllegalArgumentException("new run must be an uncommitted READY state");
        }
        return mutate(connection -> {
            if (state(connection, initial.spec().runId()).isPresent()) throw new IllegalArgumentException("run already exists: " + initial.spec().runId());
            validateDependencies(connection, initial.spec(), Set.of());
            RunState prepared = dependencyGatedState(connection, initial);
            Instant now = Instant.now();
            insertRun(connection, prepared, now, now);
            try (PreparedStatement origin = connection.prepareStatement(
                    "INSERT INTO run_origins(run_id, transaction_sequence, state_json) VALUES (?, ?, ?)")) {
                origin.setString(1, prepared.spec().runId()); origin.setLong(2, currentTransactionSequence());
                origin.setString(3, json(prepared)); origin.executeUpdate();
            }
            insertRelations(connection, prepared.spec());
            if (prepared.status() == RunStatus.READY) insertActivation(connection, prepared, 1, now);
            RuntimeEvent event = appendEvent(connection, prepared.spec().runId(), 0, "RUN_CREATED", now,
                    Map.of("graphVersion", RunState.GRAPH_VERSION));
            publishAfterCommit(connection, List.of(event));
            return prepared;
        });
    }

    @Override public RunState enqueueEvent(String runId, ExternalEvent event) {
        Objects.requireNonNull(event, "event");
        return mutate(connection -> {
            RunState current = requireState(connection, runId);
            if (current.status().terminal()) throw new IllegalStateException("terminal runs cannot accept events");
            try (PreparedStatement inbox = connection.prepareStatement("""
                    INSERT INTO inbox(event_id,run_id,transaction_sequence,event_type,correlation_id,occurred_at,event_json)
                    VALUES(?,?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING
                    """)) {
                inbox.setString(1, event.eventId()); inbox.setString(2, runId);
                inbox.setLong(3, currentTransactionSequence()); inbox.setString(4, externalType(event));
                inbox.setString(5, event.correlationId()); inbox.setString(6, event.occurredAt().toString());
                inbox.setString(7, externalJson(event));
                if (inbox.executeUpdate() == 1) {
                    appendEvent(connection, runId, current.commitSequence(), "EXTERNAL_EVENT_ENQUEUED",
                            event.occurredAt(), Map.of("eventId", event.eventId(),
                                    "eventType", externalType(event), "duringActivation", true));
                }
            }
            return current;
        });
    }

    @Override public RunState acceptEvent(ExternalEventBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return mutate(connection -> {
            RunState primary = null;
            for (ExternalEventCommit commit : batch.commits()) {
                RunState accepted = acceptEvent(connection, commit);
                if (primary == null) primary = accepted;
            }
            return Objects.requireNonNull(primary, "primary event state");
        });
    }

    private RunState acceptEvent(Connection connection, ExternalEventCommit commit) throws SQLException {
        String runId = commit.runId();
        ExternalEvent event = commit.event();
        RunState current = requireState(connection, runId);
            if (current.commitSequence() != commit.expectedCommitSequence()
                    || !RuntimeDigest.sha256(current).equals(commit.expectedStateDigest())) {
                throw new IllegalStateException("run changed before event commit: " + runId);
            }
            RunState next = commit.reduction().state();
            if (next.commitSequence() != current.commitSequence() + 1) throw new IllegalStateException("event state commit mismatch");
            int inserted;
            try (PreparedStatement inbox = connection.prepareStatement("""
                    INSERT INTO inbox(event_id, run_id, transaction_sequence, event_type, correlation_id, occurred_at, event_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(event_id) DO NOTHING
                    """)) {
                inbox.setString(1, event.eventId()); inbox.setString(2, runId);
                inbox.setLong(3, currentTransactionSequence()); inbox.setString(4, externalType(event));
                inbox.setString(5, event.correlationId()); inbox.setString(6, event.occurredAt().toString());
                inbox.setString(7, externalJson(event));
                inserted = inbox.executeUpdate();
            }
            if (inserted == 0) return current;
            insertCommit(connection, next.commitSequence(), runId, "EXTERNAL_EVENT", next.superstep(),
                    next.phase(), List.of(), commit.commands(), event.eventId(), event.occurredAt());
            if (event instanceof ExternalEvent.EffectConfirmation confirmation) {
                effects.confirm(connection, runId, commitSequence(connection, runId), confirmation,
                        (id, sequence, type, occurredAt, payload) -> appendEvent(
                                connection, id, sequence, type, occurredAt, payload));
            } else if (event instanceof ExternalEvent.ExternalActionResult externalResult) {
                effects.confirmExternal(connection, current, externalResult,
                        (id, sequence, type, occurredAt, payload) -> appendEvent(
                                connection, id, sequence, type, occurredAt, payload));
            }
            updateRun(connection, current, next, event.occurredAt());
            if (next.status() != RunStatus.RUNNING) {
                replaceActivation(connection, next, 1, event.occurredAt());
            }
            RuntimeEvent runtimeEvent = appendEvent(connection, runId, next.commitSequence(),
                    "EXTERNAL_EVENT_ACCEPTED", event.occurredAt(), Map.of("eventId", event.eventId(), "eventType", externalType(event)));
            List<RuntimeEvent> published = new ArrayList<>();
            published.add(runtimeEvent);
            if (isForkConfirmation(current, event)) confirmForkDescendants(connection, current.spec().runId(), event);
            if (next.status().terminal()) {
                if (!next.spec().parentRunId().isBlank()) {
                    crashes.at(CrashInjector.Point.CHILD_PARENT_HANDOFF,
                            Map.of("childRunId", next.spec().runId(), "parentRunId", next.spec().parentRunId()));
                    RuntimeEvent parentEvent = completeChild(connection, next, event.occurredAt());
                    if (parentEvent != null) published.add(parentEvent);
                }
                completeDependents(connection, next, event.occurredAt());
            }
            publishAfterCommit(connection, published);
        return next;
    }

    @Override public Optional<Activation> claim(String runId, String owner, Instant now, Duration leaseDuration) {
        return mutate(connection -> {
            RunState current = requireState(connection, runId);
            if (current.status() != RunStatus.READY) return Optional.empty();
            Activation activation;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT activation_id, phase, superstep, attempt FROM activations
                    WHERE run_id = ? AND status = 'READY' ORDER BY created_at, activation_id LIMIT 1
                    """)) {
                query.setString(1, runId);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    activation = new Activation(result.getString(1), runId, RuntimePhase.valueOf(result.getString(2)),
                            result.getLong(3), owner, now.plus(leaseDuration), result.getInt(4));
                }
            }
            try (PreparedStatement claim = connection.prepareStatement("""
                    UPDATE activations SET status='RUNNING', lease_owner=?, lease_expires_at=?
                    WHERE activation_id=? AND status='READY'
                    """)) {
                claim.setString(1, owner); claim.setString(2, activation.leaseExpiresAt().toString());
                claim.setString(3, activation.activationId());
                if (claim.executeUpdate() != 1) return Optional.empty();
            }
            RunState running = SqliteRuntimeStateSupport.copy(current, RunStatus.RUNNING, current.phase(), current.commitSequence(),
                    current.superstep(), current.waitReason(), current.cancelRequested());
            updateRun(connection, current, running, now);
            appendEvent(connection, runId, current.commitSequence(), "ACTIVATION_CLAIMED", now,
                    Map.of("activationId", activation.activationId(), "owner", owner));
            return Optional.of(activation);
        });
    }

    @Override public boolean renew(Activation activation, String owner, Instant now, Duration leaseDuration) {
        Objects.requireNonNull(activation, "activation");
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE activations SET lease_expires_at=?
                    WHERE activation_id=? AND status='RUNNING' AND lease_owner=? AND lease_expires_at>=?
                    """)) {
                statement.setString(1, now.plus(leaseDuration).toString());
                statement.setString(2, activation.activationId()); statement.setString(3, owner);
                statement.setString(4, now.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override public List<ExternalEvent> pendingInbox(String runId) {
        return read(connection -> {
            List<ExternalEvent> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT event_json FROM inbox WHERE run_id=? AND consumed_commit IS NULL
                    ORDER BY occurred_at, event_id
                    """)) {
                statement.setString(1, runId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) events.add(readJson(result.getString(1), ExternalEvent.class));
                }
            }
            return List.copyOf(events);
        });
    }

    @Override public RunState commit(CommitBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return mutate(connection -> {
            String runId = batch.activation().runId();
            RunState current = requireState(connection, runId);
            verifyClaim(connection, batch.activation(), batch.committedAt());
            if (!RuntimeDigest.sha256(current).equals(RuntimeDigest.sha256(batch.expectedState()))) {
                throw new IllegalStateException("run changed before commit: " + runId);
            }
            Set<String> acknowledgedEvents = new HashSet<>(batch.consumedEventIds());
            for (RuntimeCommand command : batch.commands()) {
                if (command instanceof RuntimeCommand.ExternalInterrupt interrupt) {
                    acknowledgedEvents.addAll(interrupt.eventIds());
                }
            }
            List<String> unacknowledgedEvents = pendingInboxIds(connection, runId).stream()
                    .filter(eventId -> !acknowledgedEvents.contains(eventId)).toList();
            if (!unacknowledgedEvents.isEmpty()) {
                throw new InboxChangedException(unacknowledgedEvents);
            }
            RunState next = batch.reduction().state();
            if (next.commitSequence() != current.commitSequence() + 1 || next.superstep() != current.superstep() + 1) {
                throw new IllegalStateException("superstep did not advance exactly once");
            }
            insertCommit(connection, next.commitSequence(), runId, "SUPERSTEP", next.superstep(),
                    current.phase(), batch.writes(), batch.commands(), "", batch.committedAt());
            for (String eventId : batch.consumedEventIds()) {
                crashes.at(CrashInjector.Point.INBOX_CONSUMPTION,
                        Map.of("runId", runId, "eventId", eventId));
                ExternalEvent consumedEvent = storedEvent(connection, eventId);
                if (consumedEvent instanceof ExternalEvent.EffectConfirmation confirmation) {
                    effects.confirm(connection, runId, current.commitSequence(), confirmation,
                            (id, sequence, type, occurredAt, payload) -> appendEvent(
                                    connection, id, sequence, type, occurredAt, payload));
                } else if (consumedEvent instanceof ExternalEvent.ExternalActionResult result) {
                    effects.confirmExternal(connection, current, result,
                            (id, sequence, type, occurredAt, payload) -> appendEvent(
                                    connection, id, sequence, type, occurredAt, payload));
                }
                try (PreparedStatement consumed = connection.prepareStatement("""
                        UPDATE inbox SET consumed_commit=? WHERE event_id=? AND run_id=? AND consumed_commit IS NULL
                        """)) {
                    consumed.setLong(1, next.commitSequence()); consumed.setString(2, eventId);
                    consumed.setString(3, runId); consumed.executeUpdate();
                }
            }
            Set<String> childBatchIds = batch.reduction().childRuns().stream()
                    .map(RunSpec::runId).collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (RunSpec child : batch.reduction().childRuns()) {
                if (state(connection, child.runId()).isPresent()) {
                    throw new IllegalStateException("child run already exists: " + child.runId());
                }
                validateDependencies(connection, child, childBatchIds);
            }
            for (RunSpec child : batch.reduction().childRuns()) createChild(connection, child, batch.committedAt());
            try (PreparedStatement effects = connection.prepareStatement("""
                    UPDATE effects SET committed_superstep=?, effect_json=json_set(effect_json,'$.committedSuperstep',?), updated_at=?
                    WHERE run_id=? AND activation_id=? AND committed_superstep=-1
                    """)) {
                effects.setLong(1, next.superstep()); effects.setLong(2, next.superstep());
                effects.setString(3, batch.committedAt().toString()); effects.setString(4, runId);
                effects.setString(5, batch.activation().activationId()); effects.executeUpdate();
            }
            updateRun(connection, current, next, batch.committedAt());
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM activations WHERE activation_id=?")) {
                delete.setString(1, batch.activation().activationId()); delete.executeUpdate();
            }
            if (next.status() == RunStatus.READY) insertActivation(connection, next, batch.activation().attempt() + 1, batch.committedAt());
            RuntimeEvent committed = appendEvent(connection, runId, next.commitSequence(),
                    batch.timelineType().isBlank() ? "SUPERSTEP_COMMITTED" : batch.timelineType(),
                    batch.committedAt(), batch.timelineDetail());
            List<RuntimeEvent> published = new ArrayList<>();
            published.add(committed);
            if (next.status().terminal()) {
                if (!next.spec().parentRunId().isBlank()) {
                    crashes.at(CrashInjector.Point.CHILD_PARENT_HANDOFF,
                            Map.of("childRunId", next.spec().runId(), "parentRunId", next.spec().parentRunId()));
                    RuntimeEvent parentEvent = completeChild(connection, next, batch.committedAt());
                    if (parentEvent != null) published.add(parentEvent);
                }
                completeDependents(connection, next, batch.committedAt());
            }
            publishAfterCommit(connection, published);
            return next;
        });
    }

    @Override public int recoverExpiredActivations(Instant now) {
        return mutateWhen(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM activations WHERE status='RUNNING' AND lease_expires_at < ? LIMIT 1")) {
                query.setString(1, now.toString());
                try (ResultSet result = query.executeQuery()) { return result.next(); }
            }
        }, connection -> {
            List<String> runIds = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT run_id FROM activations WHERE status='RUNNING' AND lease_expires_at < ?
                    """)) {
                query.setString(1, now.toString());
                try (ResultSet result = query.executeQuery()) { while (result.next()) runIds.add(result.getString(1)); }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE activations SET status='READY', lease_owner='', lease_expires_at=NULL
                    WHERE status='RUNNING' AND lease_expires_at < ?
                    """)) {
                update.setString(1, now.toString()); update.executeUpdate();
            }
            for (String runId : runIds) {
                RunState state = requireState(connection, runId);
                if (!state.status().terminal()) updateRun(connection, state,
                        SqliteRuntimeStateSupport.copy(state, RunStatus.READY, state.phase(), state.commitSequence(), state.superstep(), null,
                                state.cancelRequested()), now);
                appendEvent(connection, runId, state.commitSequence(), "ACTIVATION_LEASE_EXPIRED", now, Map.of());
            }
            return runIds.size();
        }, 0);
    }

    @Override public List<RunView> readyRuns(int limit) {
        int bounded = Math.max(1, Math.min(1_024, limit));
        return read(connection -> {
            List<RunView> views = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT state_json FROM runs WHERE status='READY'
                    ORDER BY updated_at, run_id LIMIT ?
                    """)) {
                statement.setInt(1, bounded);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) views.add(view(readJson(result.getString(1), RunState.class)));
                }
            }
            return List.copyOf(views);
        });
    }

    @Override public List<RunView> dueTimers(Instant now, int limit) {
        int bounded = Math.max(1, Math.min(1_024, limit));
        return read(connection -> {
            List<RunView> views = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT state_json FROM runs
                    WHERE status='WAITING' AND json_extract(state_json,'$.waitReason.type')='retry'
                      AND json_extract(state_json,'$.waitReason.dueAt') <= ?
                    ORDER BY json_extract(state_json,'$.waitReason.dueAt'), updated_at, run_id LIMIT ?
                    """)) {
                statement.setString(1, now.toString());
                statement.setInt(2, bounded);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        RunView candidate = view(readJson(result.getString(1), RunState.class));
                        if (candidate.state().waitReason() instanceof WaitReason.RetryWait retry
                                && !retry.dueAt().isAfter(now)) views.add(candidate);
                    }
                }
            }
            return List.copyOf(views);
        });
    }

    @Override public Optional<ModelInvocation> modelInvocation(String invocationId) {
        return read(connection -> models.find(connection, invocationId));
    }

    @Override public ModelInvocation saveModelInvocation(ModelInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return mutate(connection -> {
            RunState run = requireState(connection, invocation.runId());
            return models.save(connection, invocation, run, budgets, run.commitSequence(),
                    (id, sequence, type, occurredAt, payload) -> appendEvent(
                            connection, id, sequence, type, occurredAt, payload));
        });
    }

    @Override public Optional<EffectRecord> effect(String effectId) {
        return read(connection -> effects.find(connection, effectId));
    }

    @Override public EffectRecord saveEffect(EffectRecord effect) {
        Objects.requireNonNull(effect, "effect");
        return mutate(connection -> {
            return effects.save(connection, effect, commitSequence(connection, effect.intent().runId()),
                    (id, sequence, type, occurredAt, payload) -> appendEvent(
                            connection, id, sequence, type, occurredAt, payload));
        });
    }

    @Override public boolean acquireResources(String effectId, String owner, List<String> resources,
                                              Instant now, Duration leaseDuration) {
        return mutate(connection -> effects.acquire(connection, effectId, owner, resources, now, leaseDuration));
    }

    @Override public void releaseResources(String effectId, String owner) {
        mutate(connection -> { effects.release(connection, effectId, owner); return null; });
    }

    @Override public boolean renewResources(String effectId, String owner, List<String> resources,
                                            Instant now, Duration leaseDuration) {
        return mutate(connection -> effects.renew(connection, effectId, owner, resources, now, leaseDuration));
    }

    @Override public List<EffectRecord> unresolvedEffects(String runId) {
        return read(connection -> effects.unresolved(connection, runId));
    }

    @Override public void reserveToolCall(String runId, String reservationId, Instant now) {
        mutate(connection -> {
            budgets.reserveToolCall(connection, requireState(connection, runId), reservationId, now);
            return null;
        });
    }

    @Override public void reserveActiveTime(String runId, String reservationId, long millis, Instant now) {
        mutate(connection -> {
            budgets.reserveActiveTime(connection, requireState(connection, runId), reservationId, millis, now);
            return null;
        });
    }

    @Override public void settleActiveTime(String reservationId, long actualMillis, Instant now) {
        mutate(connection -> {
            budgets.settleActiveTime(connection, reservationId, actualMillis, now);
            return null;
        });
    }

    @Override public void settleToolCall(String reservationId, Instant now) {
        mutate(connection -> {
            budgets.settleToolCall(connection, reservationId, now);
            return null;
        });
    }

    @Override public BudgetUsage budgetUsage(String rootRunId) {
        return read(connection -> budgets.usage(connection, rootRunId));
    }

    @Override public List<RunView> list() {
        return read(connection -> {
            List<RunView> views = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT state_json FROM runs ORDER BY created_at, run_id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) views.add(view(readJson(result.getString(1), RunState.class)));
            }
            return List.copyOf(views);
        });
    }

    @Override public Optional<RunView> get(String runId) {
        return read(connection -> state(connection, runId).map(SqliteDurableRuntimeStore::view));
    }

    @Override public List<RunView> children(String runId) {
        return read(connection -> {
            List<RunView> children = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT r.state_json FROM runs r JOIN run_relations rel ON rel.child_run_id=r.run_id
                    WHERE rel.parent_run_id=? AND rel.relation_type='CHILD' ORDER BY r.created_at, r.run_id
                    """)) {
                statement.setString(1, runId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) children.add(view(readJson(result.getString(1), RunState.class)));
                }
            }
            return List.copyOf(children);
        });
    }

    @Override public List<RuntimeEvent> events(String runId) {
        return read(connection -> loadEvents(connection, runId, false));
    }

    @Override public List<TimelineEvent> timeline(String runId) {
        return events(runId).stream().map(event -> new TimelineEvent(event.sequence(), event.eventId(),
                event.runId(), event.type(), event.occurredAt(), event.payload())).toList();
    }

    @Override public StateReplay replay(String runId, long throughCommit) {
        return read(connection -> forkQueries.replay(connection, runId, throughCommit));
    }

    @Override public ForkSnapshot forkSnapshot(String runId, long throughCommit) {
        return read(connection -> forkQueries.snapshot(connection, runId, throughCommit));
    }

    @Override public RunState fork(ForkSpec spec, RunState forkedState, List<RunState> descendantStates) {
        Objects.requireNonNull(spec, "spec"); Objects.requireNonNull(forkedState, "forkedState");
        return mutate(connection -> {
            if (state(connection, spec.newRunId()).isPresent()) throw new IllegalArgumentException("run already exists: " + spec.newRunId());
            List<RunState> descendants = List.copyOf(descendantStates != null ? descendantStates : List.of());
            Set<String> forkRunIds = new LinkedHashSet<>();
            forkRunIds.add(forkedState.spec().runId());
            for (RunState descendant : descendants) {
                if (!forkRunIds.add(descendant.spec().runId())) {
                    throw new IllegalArgumentException("duplicate fork run: " + descendant.spec().runId());
                }
            }
            validateDependencies(connection, forkedState.spec(), forkRunIds);
            for (RunState descendant : descendants) validateDependencies(connection, descendant.spec(), forkRunIds);
            Instant now = Instant.now();
            RunState persistedRoot = forkedState.status() == RunStatus.READY
                    ? dependencyGatedState(connection, forkedState) : forkedState;
            insertRun(connection, persistedRoot, now, now);
            try (PreparedStatement origin = connection.prepareStatement(
                    "INSERT INTO run_origins(run_id,transaction_sequence,state_json) VALUES (?,?,?)")) {
                origin.setString(1, persistedRoot.spec().runId()); origin.setLong(2, currentTransactionSequence());
                origin.setString(3, json(persistedRoot)); origin.executeUpdate();
            }
            insertRelations(connection, persistedRoot.spec());
            if (persistedRoot.status() == RunStatus.READY) insertActivation(connection, persistedRoot, 1, now);
            for (RunState descendant : descendants) {
                RunState persisted = descendant.status() == RunStatus.READY
                        ? dependencyGatedState(connection, descendant) : descendant;
                insertRun(connection, persisted, now, now);
                try (PreparedStatement origin = connection.prepareStatement(
                        "INSERT INTO run_origins(run_id,transaction_sequence,state_json) VALUES (?,?,?)")) {
                    origin.setString(1, persisted.spec().runId()); origin.setLong(2, currentTransactionSequence());
                    origin.setString(3, json(persisted)); origin.executeUpdate();
                }
                insertRelations(connection, persisted.spec());
                if (persisted.status() == RunStatus.READY) insertActivation(connection, persisted, 1, now);
            }
            appendEvent(connection, persistedRoot.spec().runId(), 0, "RUN_FORKED", now,
                    Map.of("sourceRunId", spec.sourceRunId(), "throughCommit", spec.throughCommit(), "execute", spec.execute()));
            return persistedRoot;
        });
    }

    @Override public AutoCloseable subscribe(RuntimeEventSubscriber subscriber) {
        RuntimeEventSubscriber required = Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(required);
        return () -> subscribers.remove(required);
    }

    @Override public void close() { subscribers.clear(); }

    private void initialize() {
        mutate(connection -> {
            int version = detectedVersion(connection);
            if (version != 0 && version != SCHEMA_VERSION) {
                throw new IllegalStateException("unsupported runtime schema " + version);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS runtime_schema(version INTEGER PRIMARY KEY, graph_version TEXT NOT NULL, layout_fingerprint TEXT NOT NULL, created_at TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS runtime_transactions(sequence INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runs(
                          run_id TEXT PRIMARY KEY, parent_run_id TEXT NOT NULL, root_run_id TEXT NOT NULL,
                          retry_of_run_id TEXT NOT NULL, graph_version TEXT NOT NULL, status TEXT NOT NULL,
                          phase TEXT NOT NULL, superstep INTEGER NOT NULL, commit_sequence INTEGER NOT NULL,
                          state_digest TEXT NOT NULL, state_json TEXT NOT NULL, transaction_sequence INTEGER NOT NULL,
                          created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                        """);
                statement.execute("CREATE TABLE IF NOT EXISTS run_origins(run_id TEXT PRIMARY KEY, transaction_sequence INTEGER NOT NULL, state_json TEXT NOT NULL, FOREIGN KEY(run_id) REFERENCES runs(run_id))");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS activations(
                          activation_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, phase TEXT NOT NULL,
                          superstep INTEGER NOT NULL, attempt INTEGER NOT NULL, status TEXT NOT NULL,
                          lease_owner TEXT NOT NULL DEFAULT '', lease_expires_at TEXT, created_at TEXT NOT NULL,
                          UNIQUE(run_id, superstep, phase), FOREIGN KEY(run_id) REFERENCES runs(run_id))
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS inbox(
                          event_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, transaction_sequence INTEGER NOT NULL, event_type TEXT NOT NULL,
                          correlation_id TEXT NOT NULL, occurred_at TEXT NOT NULL, event_json TEXT NOT NULL,
                          consumed_commit INTEGER, FOREIGN KEY(run_id) REFERENCES runs(run_id))
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS commits(
                          run_id TEXT NOT NULL, commit_sequence INTEGER NOT NULL, kind TEXT NOT NULL,
                          superstep INTEGER NOT NULL, phase TEXT NOT NULL, writes_json TEXT NOT NULL,
                          commands_json TEXT NOT NULL, external_event_id TEXT NOT NULL, transaction_sequence INTEGER NOT NULL,
                          committed_at TEXT NOT NULL,
                          PRIMARY KEY(run_id, commit_sequence), FOREIGN KEY(run_id) REFERENCES runs(run_id))
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_writes(
                          run_id TEXT NOT NULL, commit_sequence INTEGER NOT NULL, write_index INTEGER NOT NULL,
                          superstep INTEGER NOT NULL, phase TEXT NOT NULL, write_json TEXT NOT NULL,
                          PRIMARY KEY(run_id, commit_sequence, write_index),
                          FOREIGN KEY(run_id, commit_sequence) REFERENCES commits(run_id, commit_sequence))
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS model_invocations(
                          invocation_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, activation_id TEXT NOT NULL,
                          attempt INTEGER NOT NULL, status TEXT NOT NULL, request_digest TEXT NOT NULL,
                          provider_request_id TEXT NOT NULL, invocation_json TEXT NOT NULL, updated_at TEXT NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS effects(
                          effect_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, activation_id TEXT NOT NULL,
                          status TEXT NOT NULL, idempotency_key TEXT NOT NULL UNIQUE,
                          committed_superstep INTEGER NOT NULL, effect_json TEXT NOT NULL, updated_at TEXT NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS resource_leases(
                          resource TEXT PRIMARY KEY, effect_id TEXT NOT NULL, owner TEXT NOT NULL,
                          expires_at TEXT NOT NULL, version INTEGER NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS budget_reservations(
                          reservation_id TEXT PRIMARY KEY, root_run_id TEXT NOT NULL, run_id TEXT NOT NULL,
                          kind TEXT NOT NULL, status TEXT NOT NULL,
                          reserved_tokens INTEGER NOT NULL, reserved_cost_microusd INTEGER NOT NULL,
                          reserved_tool_calls INTEGER NOT NULL, reserved_active_millis INTEGER NOT NULL,
                          consumed_tokens INTEGER NOT NULL, consumed_cost_microusd INTEGER NOT NULL,
                          consumed_tool_calls INTEGER NOT NULL, consumed_active_millis INTEGER NOT NULL,
                          cost_known INTEGER NOT NULL, updated_at TEXT NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS run_relations(
                          parent_run_id TEXT NOT NULL, child_run_id TEXT NOT NULL, relation_type TEXT NOT NULL,
                          transaction_sequence INTEGER NOT NULL,
                          related_run_id TEXT NOT NULL, PRIMARY KEY(child_run_id, relation_type, related_run_id))
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_events(
                          sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE,
                          transaction_sequence INTEGER NOT NULL,
                          run_id TEXT NOT NULL, commit_sequence INTEGER NOT NULL, event_type TEXT NOT NULL,
                          occurred_at TEXT NOT NULL, payload_json TEXT NOT NULL)
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS activations_claim_idx ON activations(run_id,status,created_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS activations_expiry_idx ON activations(status,lease_expires_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS runs_ready_idx ON runs(status,updated_at,run_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS runs_retry_idx ON runs(status,json_extract(state_json,'$.waitReason.type'),json_extract(state_json,'$.waitReason.dueAt'))");
                statement.execute("CREATE INDEX IF NOT EXISTS inbox_pending_idx ON inbox(run_id,consumed_commit,occurred_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS runtime_events_run_idx ON runtime_events(run_id,sequence)");
                statement.execute("CREATE INDEX IF NOT EXISTS effects_run_idx ON effects(run_id,status)");
                statement.execute("CREATE INDEX IF NOT EXISTS budget_root_idx ON budget_reservations(root_run_id,status)");
                statement.execute("CREATE INDEX IF NOT EXISTS budget_run_idx ON budget_reservations(run_id,status)");
            }
            try (PreparedStatement schema = connection.prepareStatement("INSERT INTO runtime_schema(version,graph_version,layout_fingerprint,created_at) VALUES (?,?,?,?) ON CONFLICT(version) DO NOTHING")) {
                schema.setInt(1, SCHEMA_VERSION); schema.setString(2, RunState.GRAPH_VERSION);
                schema.setString(3, layoutFingerprint()); schema.setString(4, Instant.now().toString()); schema.executeUpdate();
            }
            return null;
        });
    }

    private void createChild(Connection connection, RunSpec child, Instant now) throws SQLException {
        RunState childState = SqliteRuntimeStateSupport.initial(child, unresolvedDependencies(connection, child));
        insertRun(connection, childState, now, now);
        try (PreparedStatement origin = connection.prepareStatement(
                "INSERT INTO run_origins(run_id,transaction_sequence,state_json) VALUES (?,?,?)")) {
            origin.setString(1, child.runId()); origin.setLong(2, currentTransactionSequence());
            origin.setString(3, json(childState)); origin.executeUpdate();
        }
        insertRelations(connection, child);
        if (childState.status() == RunStatus.READY) insertActivation(connection, childState, 1, now);
        appendEvent(connection, child.runId(), 0, "CHILD_RUN_CREATED", now,
                Map.of("parentRunId", child.parentRunId(), "dependencies", child.dependencies()));
    }

    private RuntimeEvent completeChild(Connection connection, RunState child, Instant now) throws SQLException {
        String parentId = child.spec().parentRunId();
        String eventId = "child-completed:" + child.spec().runId();
        ExternalEvent event = new ExternalEvent.ChildRunCompleted(eventId, child.spec().runId(), now,
                Map.of("childRunId", child.spec().runId(), "status", child.status().name(),
                        "result", child.channels().getOrDefault("result", Map.of())));
        return acceptCompletion(connection, parentId, event, "CHILD_RUN_COMPLETED", now);
    }

    private void completeDependents(Connection connection, RunState completed, Instant now) throws SQLException {
        List<String> dependentIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT child_run_id FROM run_relations
                WHERE relation_type='DEPENDS_ON' AND related_run_id=? ORDER BY child_run_id
                """)) {
            statement.setString(1, completed.spec().runId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) dependentIds.add(result.getString(1));
            }
        }
        for (String dependentId : dependentIds) {
            String eventId = "dependency-completed:" + completed.spec().runId() + ":" + dependentId;
            ExternalEvent event = new ExternalEvent.ChildRunCompleted(eventId, completed.spec().runId(), now,
                    Map.of("childRunId", completed.spec().runId(), "dependencyRunId", completed.spec().runId(),
                            "status", completed.status().name(),
                            "result", completed.channels().getOrDefault("result", Map.of())));
            acceptCompletion(connection, dependentId, event, "DEPENDENCY_COMPLETED", now);
        }
    }

    private RuntimeEvent acceptCompletion(Connection connection, String targetRunId, ExternalEvent event,
                                          String eventType, Instant now) throws SQLException {
        Optional<RunState> found = state(connection, targetRunId);
        if (found.isEmpty() || found.get().status().terminal()) return null;
        RunState target = found.get();
        int inserted;
        try (PreparedStatement inbox = connection.prepareStatement("""
                INSERT INTO inbox(event_id,run_id,transaction_sequence,event_type,correlation_id,occurred_at,event_json)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING
                """)) {
            inbox.setString(1, event.eventId()); inbox.setString(2, targetRunId);
            inbox.setLong(3, currentTransactionSequence()); inbox.setString(4, externalType(event));
            inbox.setString(5, event.correlationId()); inbox.setString(6, now.toString()); inbox.setString(7, externalJson(event));
            inserted = inbox.executeUpdate();
        }
        if (inserted == 0) return null;
        if (target.status() == RunStatus.WAITING && target.waitReason() instanceof WaitReason.ChildRunWait wait
                && wait.childRunIds().contains(String.valueOf(event.payload().get("childRunId")))) {
            RunState accepted = SqliteRuntimeStateSupport.accept(target, event, List.of());
            insertCommit(connection, accepted.commitSequence(), targetRunId, "EXTERNAL_EVENT", accepted.superstep(),
                    accepted.phase(), List.of(), List.of(), event.eventId(), now);
            updateRun(connection, target, accepted, now);
            replaceActivation(connection, accepted, 1, now);
            return appendEvent(connection, targetRunId, accepted.commitSequence(), eventType, now,
                    Map.of("childRunId", event.payload().get("childRunId"),
                            "status", event.payload().get("status"),
                            "joinSatisfied", accepted.status() == RunStatus.READY));
        }
        return appendEvent(connection, targetRunId, target.commitSequence(), eventType + "_ENQUEUED", now,
                Map.of("childRunId", event.payload().get("childRunId"),
                        "status", event.payload().get("status"), "joinSatisfied", false));
    }

    private void validateDependencies(Connection connection, RunSpec spec, Set<String> batchRunIds) throws SQLException {
        if (spec.dependencies().contains(spec.runId())) {
            throw new IllegalArgumentException("run cannot depend on itself: " + spec.runId());
        }
        for (String dependency : spec.dependencies()) {
            if (!batchRunIds.contains(dependency) && state(connection, dependency).isEmpty()) {
                throw new IllegalArgumentException("dependency run not found: " + dependency);
            }
        }
    }

    private RunState dependencyGatedState(Connection connection, RunState initial) throws SQLException {
        List<String> unresolved = unresolvedDependencies(connection, initial.spec());
        return SqliteRuntimeStateSupport.dependencyGated(initial, unresolved);
    }

    private List<String> unresolvedDependencies(Connection connection, RunSpec spec) throws SQLException {
        List<String> unresolved = new ArrayList<>();
        for (String dependency : spec.dependencies()) {
            Optional<RunState> dependencyState = state(connection, dependency);
            if (dependencyState.isEmpty() || !dependencyState.get().status().terminal()) unresolved.add(dependency);
        }
        return List.copyOf(unresolved);
    }

    private void confirmForkDescendants(Connection connection, String rootRunId,
                                        ExternalEvent confirmation) throws SQLException {
        List<String> descendantIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH RECURSIVE descendants(run_id) AS (
                  SELECT child_run_id FROM run_relations
                    WHERE parent_run_id=? AND relation_type='CHILD'
                  UNION
                  SELECT relation.child_run_id FROM run_relations relation
                    JOIN descendants parent ON relation.parent_run_id=parent.run_id
                    WHERE relation.relation_type='CHILD'
                ) SELECT run_id FROM descendants ORDER BY run_id
                """)) {
            statement.setString(1, rootRunId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) descendantIds.add(result.getString(1));
            }
        }
        for (String descendantId : descendantIds) {
            RunState current = requireState(connection, descendantId);
            if (!(current.status() == RunStatus.WAITING
                    && current.waitReason() instanceof WaitReason.ApprovalWait wait
                    && wait.approvalRequestId().equals("execute-fork:" + rootRunId))) continue;
            ExternalEvent derived = new ExternalEvent.ApprovalDecision(
                    confirmation.eventId() + ":" + descendantId, confirmation.correlationId(),
                    confirmation.occurredAt(), confirmation.payload());
            try (PreparedStatement inbox = connection.prepareStatement("""
                    INSERT INTO inbox(event_id,run_id,transaction_sequence,event_type,correlation_id,occurred_at,event_json)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                inbox.setString(1, derived.eventId()); inbox.setString(2, descendantId);
                inbox.setLong(3, currentTransactionSequence()); inbox.setString(4, externalType(derived));
                inbox.setString(5, derived.correlationId()); inbox.setString(6, derived.occurredAt().toString());
                inbox.setString(7, externalJson(derived));
                inbox.executeUpdate();
            }
            List<RuntimeCommand> commands = new ArrayList<>();
            if (Boolean.parseBoolean(String.valueOf(derived.payload().getOrDefault("approved", false)))) {
                List<String> unresolved = unresolvedDependencies(connection, current.spec());
                if (!unresolved.isEmpty()) commands.add(new RuntimeCommand.Suspend(new WaitReason.ChildRunWait(
                        "dependencies:" + current.spec().runId(), unresolved)));
            }
            RunState accepted = SqliteRuntimeStateSupport.accept(current, derived, commands);
            insertCommit(connection, accepted.commitSequence(), descendantId, "EXTERNAL_EVENT",
                    accepted.superstep(), accepted.phase(), List.of(), commands, derived.eventId(), derived.occurredAt());
            updateRun(connection, current, accepted, derived.occurredAt());
            replaceActivation(connection, accepted, 1, derived.occurredAt());
            appendEvent(connection, descendantId, accepted.commitSequence(), "FORK_EXECUTION_CONFIRMED",
                    derived.occurredAt(), Map.of("rootRunId", rootRunId,
                            "approved", derived.payload().getOrDefault("approved", false)));
            if (accepted.status().terminal()) {
                if (!accepted.spec().parentRunId().isBlank()) completeChild(connection, accepted, derived.occurredAt());
                completeDependents(connection, accepted, derived.occurredAt());
            }
        }
    }

    private static boolean isForkConfirmation(RunState current, ExternalEvent event) {
        return event instanceof ExternalEvent.ApprovalDecision
                && current.waitReason() instanceof WaitReason.ApprovalWait wait
                && wait.approvalRequestId().equals("execute-fork:" + current.spec().runId());
    }

    private static boolean isApprovedForkConfirmation(RunState current, ExternalEvent event) {
        return isForkConfirmation(current, event)
                && Boolean.parseBoolean(String.valueOf(event.payload().getOrDefault("approved", false)));
    }

    private void insertRun(Connection connection, RunState state, Instant createdAt, Instant updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runs(run_id,parent_run_id,root_run_id,retry_of_run_id,graph_version,status,phase,
                  superstep,commit_sequence,state_digest,state_json,transaction_sequence,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, state.spec().runId()); statement.setString(2, state.spec().parentRunId());
            statement.setString(3, state.spec().rootRunId()); statement.setString(4, state.spec().retryOfRunId());
            statement.setString(5, state.graphVersion()); statement.setString(6, state.status().name());
            statement.setString(7, state.phase().name()); statement.setLong(8, state.superstep());
            statement.setLong(9, state.commitSequence()); statement.setString(10, stableDigest(state));
            statement.setString(11, json(state)); statement.setLong(12, currentTransactionSequence());
            statement.setString(13, createdAt.toString()); statement.setString(14, updatedAt.toString());
            statement.executeUpdate();
        }
    }

    private void updateRun(Connection connection, RunState expected, RunState next, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE runs SET status=?,phase=?,superstep=?,commit_sequence=?,state_digest=?,state_json=?,
                  transaction_sequence=?,updated_at=?
                WHERE run_id=? AND state_digest=?
                """)) {
            statement.setString(1, next.status().name()); statement.setString(2, next.phase().name());
            statement.setLong(3, next.superstep()); statement.setLong(4, next.commitSequence());
            statement.setString(5, stableDigest(next)); statement.setString(6, json(next));
            statement.setLong(7, currentTransactionSequence()); statement.setString(8, now.toString());
            statement.setString(9, expected.spec().runId()); statement.setString(10, stableDigest(expected));
            if (statement.executeUpdate() != 1) throw new IllegalStateException("run transition conflict: " + expected.spec().runId());
        }
    }

    private void insertRelations(Connection connection, RunSpec spec) throws SQLException {
        if (!spec.parentRunId().isBlank()) insertRelation(connection, spec.parentRunId(), spec.runId(), "CHILD", spec.parentRunId());
        if (!spec.retryOfRunId().isBlank()) insertRelation(connection, spec.parentRunId(), spec.runId(), "RETRY_OF", spec.retryOfRunId());
        for (String dependency : spec.dependencies()) insertRelation(connection, spec.parentRunId(), spec.runId(), "DEPENDS_ON", dependency);
    }

    private void insertRelation(Connection connection, String parent, String child, String type, String related) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_relations(parent_run_id,child_run_id,relation_type,related_run_id,transaction_sequence)
                VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, parent != null ? parent : ""); statement.setString(2, child);
            statement.setString(3, type); statement.setString(4, related);
            statement.setLong(5, currentTransactionSequence()); statement.executeUpdate();
        }
    }

    private void insertActivation(Connection connection, RunState state, int attempt, Instant now) throws SQLException {
        if (state.phase() == RuntimePhase.WAIT || state.phase() == RuntimePhase.TERMINAL) return;
        String activationId = state.spec().runId() + ":" + state.commitSequence() + ":" + state.phase().name();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO activations(activation_id,run_id,phase,superstep,attempt,status,created_at)
                VALUES(?,?,?,?,?,'READY',?) ON CONFLICT(run_id,superstep,phase) DO NOTHING
                """)) {
            statement.setString(1, activationId); statement.setString(2, state.spec().runId());
            statement.setString(3, state.phase().name()); statement.setLong(4, state.superstep());
            statement.setInt(5, attempt); statement.setString(6, now.toString()); statement.executeUpdate();
        }
    }

    private void replaceActivation(Connection connection, RunState state, int attempt, Instant now) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM activations WHERE run_id=?")) {
            delete.setString(1, state.spec().runId()); delete.executeUpdate();
        }
        if (state.status() == RunStatus.READY) insertActivation(connection, state, attempt, now);
    }

    private void insertCommit(Connection connection, long sequence, String runId, String kind, long superstep,
                              RuntimePhase phase, List<ChannelWrite> writes, List<RuntimeCommand> commands,
                              String externalEventId, Instant now) throws SQLException {
        journal.insertCommit(connection, sequence, runId, kind, superstep, phase, writes, commands,
                externalEventId, now, currentTransactionSequence());
    }

    private void verifyClaim(Connection connection, Activation activation, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT phase,superstep,lease_owner,lease_expires_at,status FROM activations WHERE activation_id=?
                """)) {
            statement.setString(1, activation.activationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"RUNNING".equals(result.getString("status"))
                        || !activation.leaseOwner().equals(result.getString("lease_owner"))
                        || activation.phase() != RuntimePhase.valueOf(result.getString("phase"))
                        || activation.superstep() != result.getLong("superstep")
                        || result.getString("lease_expires_at") == null
                        || Instant.parse(result.getString("lease_expires_at")).isBefore(now)) {
                    throw new IllegalStateException("activation lease was lost: " + activation.activationId());
                }
            }
        }
    }

    private Optional<RunState> state(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT state_json,state_digest FROM runs WHERE run_id=?")) {
            statement.setString(1, required(runId, "runId"));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                RunState state = readJson(result.getString(1), RunState.class);
                if (!stableDigest(state).equals(result.getString(2))) throw new IllegalStateException("run state digest mismatch: " + runId);
                return Optional.of(state);
            }
        }
    }

    private RunState requireState(Connection connection, String runId) throws SQLException {
        return state(connection, runId).orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }

    private long commitSequence(Connection connection, String runId) throws SQLException {
        return requireState(connection, runId).commitSequence();
    }

    private ExternalEvent storedEvent(Connection connection, String eventId) throws SQLException {
        return journal.storedEvent(connection, eventId);
    }

    private List<String> pendingInboxIds(Connection connection, String runId) throws SQLException {
        return journal.pendingInboxIds(connection, runId);
    }

    private List<RuntimeEvent> loadEvents(Connection connection, String runId, boolean all) throws SQLException {
        return journal.events(connection, runId, all);
    }

    private RuntimeEvent appendEvent(Connection connection, String runId, long commitSequence, String type,
                                     Instant occurredAt, Map<String, Object> payload) throws SQLException {
        RuntimeEvent event = journal.appendEvent(connection, runId, commitSequence, type, occurredAt,
                payload, currentTransactionSequence());
        transactions.publishAfterCommit(event);
        return event;
    }

    /** Retained at call sites to document the transaction boundary; publication is handled by mutate(). */
    private static void publishAfterCommit(Connection connection, List<RuntimeEvent> events) {
        Objects.requireNonNull(connection, "connection"); Objects.requireNonNull(events, "events");
    }

    private static RunView view(RunState state) {
        return new RunView(state, state.commitSequence(), RuntimeDigest.sha256(state));
    }

    private static String externalType(ExternalEvent event) {
        return event.getClass().getSimpleName();
    }

    private <T> T read(SqliteRuntimeTransactionFacade.SqlWork<T> work) {
        return transactions.read(work);
    }

    private <T> T mutate(SqliteRuntimeTransactionFacade.SqlWork<T> work) {
        SqliteRuntimeTransactionFacade.Mutation<T> mutation = transactions.mutate(work);
        // Publication is deliberately outside the transaction/error scope. A subscriber is an
        // observation sink: it cannot roll back an already committed mutation or make the caller
        // believe that the mutation failed. Isolate subscribers from one another as well.
        for (RuntimeEvent event : mutation.events()) {
            for (RuntimeEventSubscriber subscriber : subscribers) {
                try { subscriber.onEvent(event); }
                catch (RuntimeException ignored) { /* durable state remains authoritative */ }
            }
        }
        return mutation.value();
    }

    private <T> T mutateWhen(SqliteRuntimeTransactionFacade.SqlPredicate condition,
                             SqliteRuntimeTransactionFacade.SqlWork<T> work, T unchanged) {
        SqliteRuntimeTransactionFacade.Mutation<T> mutation = transactions.mutateWhen(condition, work, unchanged);
        for (RuntimeEvent event : mutation.events()) {
            for (RuntimeEventSubscriber subscriber : subscribers) {
                try { subscriber.onEvent(event); }
                catch (RuntimeException ignored) { /* durable state remains authoritative */ }
            }
        }
        return mutation.value();
    }

    private long currentTransactionSequence() {
        return transactions.currentSequence();
    }

    private static String layoutFingerprint() { return LAYOUT_FINGERPRINT; }

    private static int detectedVersion(Connection connection) throws SQLException {
        if (tableExists(connection, "runtime_schema")) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM runtime_schema")) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
        if (tableExists(connection, "schema_migrations")) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            return result.next() && result.getInt(1) == 0 ? 0 : -1;
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static void rebuildKnownLegacyV3IfNeeded(Path database) throws IOException {
        if (!Files.exists(database) || fileSize(database) == 0) return;
        boolean rebuild = false;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            if (detectedVersion(connection) != SCHEMA_VERSION) return;
            if (columnExists(connection, "runtime_schema", "layout_fingerprint")) {
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery(
                             "SELECT layout_fingerprint FROM runtime_schema WHERE version=3")) {
                    if (!result.next() || !LAYOUT_FINGERPRINT.equals(result.getString(1))) {
                        throw new IllegalStateException("unknown runtime schema-v3 layout at " + database);
                    }
                }
                return;
            }
            Set<String> legacyTables = Set.of("runtime_schema", "runs", "run_origins", "activations",
                    "inbox", "commits", "channel_writes", "model_invocations", "effects",
                    "resource_leases", "run_relations", "runtime_events");
            for (String table : legacyTables) {
                if (!tableExists(connection, table)) {
                    throw new IllegalStateException("unknown runtime schema-v3 layout at " + database);
                }
            }
            Set<String> actualTables = new LinkedHashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                while (result.next()) actualTables.add(result.getString(1));
            }
            if (!legacyTables.equals(actualTables)) {
                throw new IllegalStateException("unknown runtime schema-v3 layout at " + database);
            }
            if (hasActiveLease(connection, "activations", "status='RUNNING' AND lease_expires_at>=?")
                    || hasActiveLease(connection, "resource_leases", "expires_at>=?")) {
                throw new IllegalStateException("runtime schema-v3 database has an active lease; refusing rebuild: "
                        + database);
            }
            rebuild = true;
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot inspect runtime schema-v3 layout at " + database, failure);
        }
        if (rebuild) {
            LOG.warn("Destructive legacy schema-v3 rebuild selected; deleting database and WAL files without backup: {}",
                    database);
            Files.deleteIfExists(database);
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(Path.of(database + "-shm"));
        }
    }

    private static boolean hasActiveLease(Connection connection, String table, String predicate)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            statement.setString(1, Instant.now().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getLong(1) > 0L;
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equalsIgnoreCase(result.getString("name"))) return true;
            return false;
        }
    }

    private static void archiveV5IfNeeded(Path database) throws IOException {
        if (!Files.exists(database) || fileSize(database) == 0) return;
        String url = "jdbc:sqlite:" + database;
        int version;
        try (Connection connection = DriverManager.getConnection(url)) {
            version = detectedVersion(connection);
            if (version == SCHEMA_VERSION) return;
            if (version != 2) throw new IllegalStateException("unknown or corrupt runtime schema " + version + " at " + database);
            if (tableExists(connection, "runtime_instances")) {
                try (PreparedStatement active = connection.prepareStatement("""
                        SELECT COUNT(*) FROM runtime_instances WHERE status='ACTIVE' AND expires_at>=?
                        """)) {
                    active.setString(1, Instant.now().toString());
                    try (ResultSet result = active.executeQuery()) {
                        if (result.next() && result.getLong(1) > 0) {
                            throw new IllegalStateException("v5 runtime database has an active lease; refusing archive: " + database);
                        }
                    }
                }
            }
            try (Statement checkpoint = connection.createStatement()) { checkpoint.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot inspect legacy runtime database: " + database, failure);
        }
        String digest = fileDigest(database);
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Path archive = database.getParent().resolve("archive").resolve("runtime-v5-" + stamp + "-" + digest.substring(0, 12));
        Files.createDirectories(archive);
        moveIfPresent(database, archive.resolve(database.getFileName()));
        moveIfPresent(Path.of(database + "-wal"), archive.resolve(database.getFileName() + "-wal"));
        moveIfPresent(Path.of(database + "-shm"), archive.resolve(database.getFileName() + "-shm"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("originalPath", database.toString()); manifest.put("schemaVersion", 2);
        manifest.put("sha256", digest); manifest.put("archivedAt", Instant.now().toString());
        Files.writeString(archive.resolve("manifest.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
    }

    private static void moveIfPresent(Path source, Path target) throws IOException {
        if (Files.exists(source)) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long fileSize(Path path) {
        try { return Files.size(path); }
        catch (IOException failure) { throw new IllegalStateException("cannot stat runtime database", failure); }
    }

    private static String fileDigest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("cannot digest runtime database", failure);
        }
    }

    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot encode runtime value", failure); }
    }

    private static String externalJson(ExternalEvent value) {
        try { return MAPPER.writerFor(ExternalEvent.class).writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot encode external event", failure); }
    }

    private static String stableDigest(Object value) {
        try { return RuntimeDigest.sha256(MAPPER.readTree(json(value))); }
        catch (Exception failure) { throw new IllegalStateException("cannot digest runtime value", failure); }
    }

    private static <T> T readJson(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode runtime value", failure); }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

}
