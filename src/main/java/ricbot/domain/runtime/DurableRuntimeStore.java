package ricbot.domain.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port. Implementations must make each mutating method transactional. */
public interface DurableRuntimeStore extends RunQuery, AutoCloseable {
    RunState create(RunState initial);
    RunState enqueueEvent(String runId, ExternalEvent event);
    RunState acceptEvent(ExternalEventBatch batch);
    Optional<Activation> claim(String runId, String owner, Instant now, Duration leaseDuration);
    boolean renew(Activation activation, String owner, Instant now, Duration leaseDuration);
    List<ExternalEvent> pendingInbox(String runId);
    RunState commit(CommitBatch batch);
    List<RunView> readyRuns(int limit);
    List<RunView> dueTimers(Instant now, int limit);
    int recoverExpiredActivations(Instant now);

    Optional<ModelInvocation> modelInvocation(String invocationId);
    ModelInvocation saveModelInvocation(ModelInvocation invocation);
    Optional<EffectRecord> effect(String effectId);
    EffectRecord saveEffect(EffectRecord effect);
    boolean acquireResources(String effectId, String owner, List<String> resources, Instant now, Duration leaseDuration);
    boolean renewResources(String effectId, String owner, List<String> resources, Instant now, Duration leaseDuration);
    void releaseResources(String effectId, String owner);
    List<EffectRecord> unresolvedEffects(String runId);

    void reserveToolCall(String runId, String reservationId, Instant now);
    void reserveActiveTime(String runId, String reservationId, long millis, Instant now);
    void settleActiveTime(String reservationId, long actualMillis, Instant now);
    void settleToolCall(String reservationId, Instant now);
    BudgetUsage budgetUsage(String rootRunId);

    StateReplay replay(String runId, long throughCommit);
    ForkSnapshot forkSnapshot(String runId, long throughCommit);
    RunState fork(ForkSpec spec, RunState forkedState, List<RunState> descendantStates);
    AutoCloseable subscribe(RuntimeEventSubscriber subscriber);
    @Override void close();
}
