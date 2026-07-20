package ricbot.domain.agent;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Enforces durable idempotency, explicit uncertain retries, and compensation. */
public final class SideEffectCoordinator {
    private final SideEffectStore store;

    public SideEffectCoordinator(SideEffectStore store) {
        this.store = store != null ? store : SideEffectStore.disabled();
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
        Objects.requireNonNull(tools, "tools");
        if (readOnly) {
            return new SideEffectOutcome(tools.execute(toolName, arguments), false, null);
        }
        String digest = ToolInvocationRecord.argumentsDigest(arguments);
        SideEffectRecord candidate = SideEffectRecord.reserved(
                idempotencyKey, sessionKey, toolName, digest);
        SideEffectClaim claim = store.claim(candidate);
        SideEffectRecord record = claim.record();
        validateIdentity(record, sessionKey, toolName, digest);
        if (record.status() == SideEffectStatus.SUCCEEDED || record.status() == SideEffectStatus.FAILED) {
            return new SideEffectOutcome(record.result(), true, record);
        }
        if (record.status() == SideEffectStatus.COMPENSATED) {
            throw new IllegalStateException("compensated side effect requires a new idempotency key");
        }
        if (!claim.created() && record.status() == SideEffectStatus.RESERVED) {
            throw new SideEffectConfirmationRequiredException(idempotencyKey);
        }
        String confirmationId = record.status() == SideEffectStatus.RETRY_AUTHORIZED
                ? record.confirmationId() : "";
        if (record.status() == SideEffectStatus.RETRY_AUTHORIZED) {
            record = store.save(record.withStatus(SideEffectStatus.RESERVED, null, confirmationId));
        }
        Object result = tools.executeProtocol(toolName, arguments, idempotencyKey, confirmationId);
        SideEffectStatus status = succeeded != null && succeeded.test(result)
                ? SideEffectStatus.SUCCEEDED : SideEffectStatus.FAILED;
        SideEffectRecord completed = store.save(record.withStatus(status, result, confirmationId));
        return new SideEffectOutcome(result, false, completed);
    }

    public SideEffectRecord authorizeRetry(String idempotencyKey, String confirmationId) {
        if (confirmationId == null || confirmationId.isBlank()) {
            throw new IllegalArgumentException("confirmationId is required");
        }
        SideEffectRecord record = store.load(idempotencyKey).orElseThrow(() ->
                new IllegalArgumentException("side effect does not exist"));
        if (record.status() != SideEffectStatus.RESERVED) {
            throw new IllegalStateException("only an uncertain reserved side effect can be retried");
        }
        return store.save(record.withStatus(SideEffectStatus.RETRY_AUTHORIZED, null, confirmationId));
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
        if (record.status() != SideEffectStatus.SUCCEEDED) {
            throw new IllegalStateException("only successful effects can be compensated");
        }
        if (!record.argumentsDigest().equals(ToolInvocationRecord.argumentsDigest(originalArguments))) {
            throw new IllegalArgumentException("compensation arguments do not match the original effect");
        }
        Tool tool = tools.get(record.toolName());
        if (tool == null || !tool.supportsCompensation()) {
            throw new IllegalStateException("tool does not support compensation: " + record.toolName());
        }
        Object result = tools.compensate(record.toolName(), originalArguments, record.result(),
                idempotencyKey, approvalId);
        return store.save(record.withStatus(SideEffectStatus.COMPENSATED, result, approvalId));
    }

    private static void validateIdentity(
            SideEffectRecord record, String sessionKey, String toolName, String digest) {
        if (!record.sessionKey().equals(sessionKey)
                || !record.toolName().equals(toolName)
                || !record.argumentsDigest().equals(digest)) {
            throw new IllegalStateException("idempotency key was reused for a different side effect");
        }
    }
}
