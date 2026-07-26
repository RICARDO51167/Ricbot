package ricbot.domain.agent;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.api.ToolEffectPolicy;
import ricbot.tool.api.ToolStateProbe;
import ricbot.domain.runtime.RuntimeDigest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;

/** Enforces durable idempotency, explicit uncertain retries, and compensation. */
public final class SideEffectCoordinator {
    private final SideEffectStore store;
    private final String instanceId;
    private final Duration executionLease;

    public SideEffectCoordinator(SideEffectStore store) {
        this(store, "standalone-" + UUID.randomUUID(), Duration.ofSeconds(30));
    }

    public SideEffectCoordinator(SideEffectStore store, String instanceId, Duration executionLease) {
        this.store = store != null ? store : SideEffectStore.disabled();
        this.instanceId = required(instanceId, "instanceId");
        this.executionLease = executionLease != null && !executionLease.isNegative() && !executionLease.isZero()
                ? executionLease : Duration.ofSeconds(30);
    }

    public Optional<SideEffectRecord> load(String idempotencyKey) {
        return store.load(idempotencyKey);
    }

    public SideEffectOutcome execute(
            ToolRegistry tools,
            String sessionKey,
            String idempotencyKey,
            String toolName,
            Map<String, Object> arguments,
            boolean readOnly,
            Predicate<Object> succeeded
    ) {
        return execute(tools, SideEffectExecutionIdentity.session(sessionKey), idempotencyKey, toolName,
                arguments, policy(tools, toolName, readOnly), succeeded, ignored -> false);
    }

    public SideEffectOutcome execute(
            ToolRegistry tools,
            String sessionKey,
            String idempotencyKey,
            String toolName,
            Map<String, Object> arguments,
            boolean readOnly,
            Predicate<Object> succeeded,
            Predicate<Object> awaitingApproval
    ) {
        return execute(tools, SideEffectExecutionIdentity.session(sessionKey), idempotencyKey, toolName,
                arguments, policy(tools, toolName, readOnly), succeeded, awaitingApproval);
    }

    public SideEffectOutcome execute(
            ToolRegistry tools,
            SideEffectExecutionIdentity identity,
            String idempotencyKey,
            String toolName,
            Map<String, Object> arguments,
            boolean readOnly,
            Predicate<Object> succeeded,
            Predicate<Object> awaitingApproval
    ) {
        return execute(tools, identity, idempotencyKey, toolName, arguments,
                policy(tools, toolName, readOnly), succeeded, awaitingApproval);
    }

    public SideEffectOutcome execute(
            ToolRegistry tools,
            SideEffectExecutionIdentity identity,
            String idempotencyKey,
            String toolName,
            Map<String, Object> arguments,
            ToolEffectPolicy policy,
            Predicate<Object> succeeded,
            Predicate<Object> awaitingApproval
    ) {
        Objects.requireNonNull(tools, "tools");
        SideEffectExecutionIdentity owner = identity != null ? identity : SideEffectExecutionIdentity.session("runtime");
        ToolEffectPolicy requiredPolicy = Objects.requireNonNull(policy, "tool policy");
        if (!requiredPolicy.declared()) throw new IllegalStateException("tool policy is undeclared: " + toolName);
        if (requiredPolicy.readOnly()) {
            Object result = ToolPolicyExecutor.invoke(requiredPolicy, owner, toolName, () ->
                    tools.executeProtocolChecked(toolName, arguments, idempotencyKey, ""));
            return new SideEffectOutcome(result, false, null);
        }
        String digest = ToolInvocationRecord.argumentsDigest(arguments);
        SideEffectRecord candidate = SideEffectRecord.reserved(idempotencyKey, owner.runId(), owner.sessionId(),
                owner.taskId(), owner.activationId(), toolName, digest, arguments);
        SideEffectClaim claim = store.claim(candidate);
        SideEffectRecord record = claim.record();
        validateIdentity(record, owner.sessionId(), toolName, digest);
        if (record.status() == SideEffectStatus.SUCCEEDED || record.status() == SideEffectStatus.FAILED) {
            return new SideEffectOutcome(record.result(), true, record);
        }
        if (record.status() == SideEffectStatus.COMPENSATED) {
            throw new IllegalStateException("compensated side effect requires a new idempotency key");
        }
        if (record.status() == SideEffectStatus.AWAITING_APPROVAL) {
            return new SideEffectOutcome(record.result(), true, record);
        }
        if (record.status() == SideEffectStatus.EXECUTING) {
            throw new SideEffectConfirmationRequiredException(idempotencyKey);
        }
        if (record.status() == SideEffectStatus.UNKNOWN) {
            if (requiredPolicy.stateProbe()) {
                Map<String, Object> probeArguments = record.arguments();
                ToolStateProbe probe = ToolPolicyExecutor.invoke(requiredPolicy, owner, toolName, () ->
                        tools.probeProtocolChecked(toolName, probeArguments, idempotencyKey));
                if (probe.outcome() == ToolStateProbe.Outcome.EXECUTED) {
                    SideEffectRecord observed = record.clearLease(SideEffectStatus.SUCCEEDED,
                            probe.observedResult(), "state-probe");
                    SideEffectRecord completed = store.transition(observed, record.version(),
                            Set.of(SideEffectStatus.UNKNOWN));
                    return new SideEffectOutcome(completed.result(), true, completed);
                }
                if (probe.outcome() == ToolStateProbe.Outcome.NOT_EXECUTED) {
                    record = authorizeRetry(idempotencyKey, "state-probe-not-executed");
                }
            }
            if (record.status() == SideEffectStatus.UNKNOWN && requiredPolicy.downstreamIdempotencyKey()) {
                record = authorizeRetry(idempotencyKey, "downstream-idempotency");
            }
            if (record.status() == SideEffectStatus.UNKNOWN) {
                throw new SideEffectConfirmationRequiredException(idempotencyKey);
            }
        }
        // RESERVED is the durable proof that the external call has not started. Competing
        // runtimes race on the following version CAS; only EXECUTING/UNKNOWN require recovery.
        String confirmationId = record.status() == SideEffectStatus.RETRY_AUTHORIZED
                ? record.confirmationId() : "";
        if (record.status() == SideEffectStatus.RETRY_AUTHORIZED) {
            SideEffectRecord next = record.clearLease(SideEffectStatus.RESERVED, null, confirmationId);
            record = store.transition(next, record.version(), Set.of(SideEffectStatus.RETRY_AUTHORIZED));
        }
        SideEffectRecord executing = record.claimExecution(instanceId, Instant.now().plus(executionLease));
        record = store.transition(executing, record.version(), Set.of(SideEffectStatus.RESERVED));
        Object result;
        try {
            result = ToolPolicyExecutor.invoke(requiredPolicy, owner, toolName, () ->
                    tools.executeProtocolChecked(toolName, arguments, idempotencyKey, confirmationId));
        } catch (RuntimeException | Error failure) {
            store.transition(record.clearLease(SideEffectStatus.UNKNOWN,
                    Map.of("errorType", failure.getClass().getName(), "message",
                            failure.getMessage() != null ? failure.getMessage() : "external call failed"), confirmationId),
                    record.version(), Set.of(SideEffectStatus.EXECUTING));
            throw failure;
        }
        if (awaitingApproval != null && awaitingApproval.test(result)) {
            SideEffectRecord pending = store.transition(
                    record.clearLease(SideEffectStatus.AWAITING_APPROVAL, result, ""), record.version(),
                    Set.of(SideEffectStatus.EXECUTING));
            return new SideEffectOutcome(result, false, pending);
        }
        SideEffectStatus status = succeeded != null && succeeded.test(result)
                ? SideEffectStatus.SUCCEEDED : SideEffectStatus.FAILED;
        SideEffectRecord completed = store.transition(record.clearLease(status, result, confirmationId),
                record.version(), Set.of(SideEffectStatus.EXECUTING));
        return new SideEffectOutcome(result, false, completed);
    }

    public SideEffectRecord authorizeApproval(String idempotencyKey, String approvalId) {
        if (approvalId == null || approvalId.isBlank()) throw new IllegalArgumentException("approvalId is required");
        SideEffectRecord record = store.load(idempotencyKey).orElseThrow(() ->
                new IllegalArgumentException("side effect does not exist"));
        if (record.status() == SideEffectStatus.RETRY_AUTHORIZED
                && approvalId.equals(record.confirmationId())) return record;
        if (record.status() != SideEffectStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("side effect is not awaiting approval");
        }
        SideEffectRecord next = record.clearLease(SideEffectStatus.RETRY_AUTHORIZED, record.result(), approvalId);
        return store.transition(next, record.version(), Set.of(SideEffectStatus.AWAITING_APPROVAL));
    }

    public SideEffectRecord reserveApproval(String sessionKey, String idempotencyKey, String toolName,
                                            Map<String, Object> arguments, Object approvalEvidence) {
        return reserveApproval(SideEffectExecutionIdentity.session(sessionKey), idempotencyKey, toolName,
                arguments, approvalEvidence);
    }

    public SideEffectRecord reserveApproval(SideEffectExecutionIdentity identity, String idempotencyKey,
                                            String toolName, Map<String, Object> arguments,
                                            Object approvalEvidence) {
        SideEffectExecutionIdentity owner = identity != null ? identity : SideEffectExecutionIdentity.session("runtime");
        String digest = ToolInvocationRecord.argumentsDigest(arguments);
        SideEffectRecord candidate = SideEffectRecord.reserved(idempotencyKey, owner.runId(), owner.sessionId(),
                owner.taskId(), owner.activationId(), toolName, digest, arguments);
        SideEffectClaim claim = store.claim(candidate);
        SideEffectRecord record = claim.record();
        validateIdentity(record, owner.sessionId(), toolName, digest);
        if (record.status() == SideEffectStatus.AWAITING_APPROVAL
                || record.status() == SideEffectStatus.RETRY_AUTHORIZED
                || record.status() == SideEffectStatus.SUCCEEDED) return record;
        SideEffectRecord next = record.clearLease(SideEffectStatus.AWAITING_APPROVAL, approvalEvidence, "");
        return store.transition(next, record.version(), Set.of(SideEffectStatus.RESERVED));
    }

    public SideEffectRecord authorizeRetry(String idempotencyKey, String confirmationId) {
        if (confirmationId == null || confirmationId.isBlank()) {
            throw new IllegalArgumentException("confirmationId is required");
        }
        SideEffectRecord record = store.load(idempotencyKey).orElseThrow(() ->
                new IllegalArgumentException("side effect does not exist"));
        if (record.status() != SideEffectStatus.UNKNOWN) {
            throw new IllegalStateException("only an unknown side effect can be retried");
        }
        SideEffectRecord next = record.clearLease(SideEffectStatus.RETRY_AUTHORIZED, null, confirmationId);
        return store.transition(next, record.version(), Set.of(SideEffectStatus.UNKNOWN));
    }

    public SideEffectRecord compensate(
            ToolRegistry tools,
            String idempotencyKey,
            Map<String, Object> originalArguments,
            String approvalId
    ) {
        if (approvalId == null || approvalId.isBlank()) throw new IllegalArgumentException("approvalId is required");
        SideEffectRecord record = store.load(idempotencyKey).orElseThrow(() ->
                new IllegalArgumentException("side effect does not exist"));
        if (record.status() == SideEffectStatus.COMPENSATED) return record;
        if (record.status() != SideEffectStatus.SUCCEEDED) {
            throw new IllegalStateException("only successful effects can be compensated");
        }
        if (!record.argumentsDigest().equals(ToolInvocationRecord.argumentsDigest(originalArguments))) {
            throw new IllegalArgumentException("compensation arguments do not match the original effect");
        }
        Tool tool = tools.get(record.toolName());
        if (tool == null || !tool.effectPolicy().compensation()) {
            throw new IllegalStateException("tool does not support compensation: " + record.toolName());
        }
        String controlKey = compensationKey(idempotencyKey);
        Map<String, Object> controlArguments = Map.of(
                "originalIdempotencyKey", idempotencyKey,
                "originalArguments", originalArguments,
                "previousResultDigest", RuntimeDigest.sha256(record.result()));
        SideEffectRecord candidate = SideEffectRecord.reserved(controlKey, record.runId(), record.sessionKey(),
                record.taskId(), record.activationId(), "compensate:" + record.toolName(),
                ToolInvocationRecord.argumentsDigest(controlArguments), controlArguments);
        SideEffectClaim claim = store.claim(candidate);
        SideEffectRecord control = claim.record();
        if (!control.sessionKey().equals(candidate.sessionKey())
                || !control.toolName().equals(candidate.toolName())
                || !control.argumentsDigest().equals(candidate.argumentsDigest())) {
            throw new IllegalStateException("compensation idempotency key was reused for another effect");
        }
        if (control.status() == SideEffectStatus.SUCCEEDED) {
            return finishCompensation(record, control.result(), approvalId);
        }
        if (control.status() == SideEffectStatus.EXECUTING) {
            throw new SideEffectConfirmationRequiredException(controlKey);
        }
        if (control.status() == SideEffectStatus.UNKNOWN || control.status() == SideEffectStatus.FAILED) {
            if (approvalId.equals(control.confirmationId())) {
                throw new SideEffectConfirmationRequiredException(controlKey);
            }
            SideEffectRecord authorized = control.clearLease(SideEffectStatus.RETRY_AUTHORIZED,
                    control.result(), approvalId);
            control = store.transition(authorized, control.version(),
                    Set.of(SideEffectStatus.UNKNOWN, SideEffectStatus.FAILED));
        }
        if (control.status() == SideEffectStatus.RETRY_AUTHORIZED) {
            SideEffectRecord reserved = control.clearLease(SideEffectStatus.RESERVED, null, approvalId);
            control = store.transition(reserved, control.version(), Set.of(SideEffectStatus.RETRY_AUTHORIZED));
        }
        if (control.status() != SideEffectStatus.RESERVED) {
            throw new IllegalStateException("compensation control requires RESERVED status");
        }
        if (!approvalId.equals(control.confirmationId())) {
            SideEffectRecord approved = control.clearLease(SideEffectStatus.RESERVED, control.result(), approvalId);
            control = store.transition(approved, control.version(), Set.of(SideEffectStatus.RESERVED));
        }
        SideEffectRecord executing = control.claimExecution(instanceId, Instant.now().plus(executionLease),
                Map.of("originalIdempotencyKey", idempotencyKey,
                        "previousResultDigest", RuntimeDigest.sha256(record.result())));
        control = store.transition(executing, control.version(), Set.of(SideEffectStatus.RESERVED));
        Object result;
        try {
            SideEffectExecutionIdentity identity = new SideEffectExecutionIdentity(record.runId(),
                    record.sessionKey(), record.taskId(), record.activationId());
            SideEffectRecord original = record;
            result = ToolPolicyExecutor.invoke(tool.effectPolicy(), identity, "compensate:" + record.toolName(),
                    () -> tools.compensate(original.toolName(), originalArguments, original.result(),
                            idempotencyKey, approvalId));
            if (result instanceof String text && (text.startsWith("Error:") || text.startsWith("错误"))
                    || result instanceof Map<?, ?> map && (map.containsKey("error")
                    || Boolean.FALSE.equals(map.get("ok")))) {
                throw new IllegalStateException("compensation did not succeed: " + result);
            }
        } catch (RuntimeException | Error failure) {
            store.transition(control.clearLease(SideEffectStatus.UNKNOWN,
                            Map.of("errorType", failure.getClass().getName(), "message",
                                    failure.getMessage() != null ? failure.getMessage() : "compensation failed"),
                            approvalId),
                    control.version(), Set.of(SideEffectStatus.EXECUTING));
            throw failure;
        }
        SideEffectRecord completedControl = store.transition(
                control.clearLease(SideEffectStatus.SUCCEEDED, result, approvalId), control.version(),
                Set.of(SideEffectStatus.EXECUTING));
        return finishCompensation(record, completedControl.result(), approvalId);
    }

    public static String compensationKey(String idempotencyKey) {
        return "compensation-" + RuntimeDigest.sha256(required(idempotencyKey, "idempotencyKey")).substring(0, 32);
    }

    private SideEffectRecord finishCompensation(SideEffectRecord original, Object result, String approvalId) {
        SideEffectRecord current = store.load(original.idempotencyKey()).orElseThrow();
        if (current.status() == SideEffectStatus.COMPENSATED) return current;
        if (current.status() != SideEffectStatus.SUCCEEDED) {
            throw new IllegalStateException("original side effect changed while compensation was executing");
        }
        return store.transition(current.clearLease(SideEffectStatus.COMPENSATED, result, approvalId),
                current.version(), Set.of(SideEffectStatus.SUCCEEDED));
    }

    private static void validateIdentity(
            SideEffectRecord record, String sessionKey, String toolName, String digest) {
        if (!record.sessionKey().equals(sessionKey)
                || !record.toolName().equals(toolName)
                || !record.argumentsDigest().equals(digest)) {
            throw new IllegalStateException("idempotency key was reused for a different side effect");
        }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    private static ToolEffectPolicy policy(ToolRegistry tools, String toolName, boolean readOnly) {
        Tool tool = tools != null ? tools.get(toolName) : null;
        if (tool != null && tool.effectPolicy().declared()) return tool.effectPolicy();
        return readOnly ? ToolEffectPolicy.readOnly(Duration.ofMinutes(5)) : ToolEffectPolicy.undeclared();
    }
}
