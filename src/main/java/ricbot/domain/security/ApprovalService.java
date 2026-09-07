package ricbot.domain.security;

import ricbot.domain.change.PendingChangeAction;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ricbot.infra.runtime.SqliteRuntimeStore;

public class ApprovalService {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;
    private final ApprovalRequestStore store;
    private TraceStore traceStore;

    public ApprovalService(ApprovalRequestStore store) {
        this(store, null, DEFAULT_TTL, Clock.systemUTC());
    }

    public ApprovalService(ApprovalRequestStore store, TraceStore traceStore) {
        this(store, traceStore, DEFAULT_TTL, Clock.systemUTC());
    }

    public ApprovalService(ApprovalRequestStore store, Duration ttl, Clock clock) {
        this(store, null, ttl, clock);
    }

    private ApprovalService(ApprovalRequestStore store, TraceStore traceStore, Duration ttl, Clock clock) {
        this.traceStore = traceStore;
        this.ttl = ttl != null ? ttl : DEFAULT_TTL;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.store = java.util.Objects.requireNonNull(store, "store");
        store.list().forEach(request -> requests.put(request.requestId(), request));
    }

    public void setTraceStore(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    public ApprovalRequest createRequest(RiskAssessment assessment) {
        ApprovalRequest request = createBareRequest(assessment);
        traceApproval(request, TraceEventType.APPROVAL_REQUESTED, "approval requested");
        return request;
    }

    public ApprovalRequest createRequest(RiskAssessment assessment, PendingToolCall pendingToolCall) {
        return createRequest(assessment, pendingToolCall, ApprovalBinding.unbound());
    }

    public ApprovalRequest createRequest(RiskAssessment assessment, PendingToolCall pendingToolCall,
                                         ApprovalBinding binding) {
        if (binding != null && binding.bound()) {
            ApprovalRequest existing = requests.values().stream()
                    .filter(candidate -> candidate.binding() != null && candidate.binding().bound())
                    .filter(candidate -> candidate.binding().runId().equals(binding.runId()))
                    .filter(candidate -> candidate.binding().idempotencyKey().equals(binding.idempotencyKey()))
                    .filter(candidate -> candidate.binding().actionType().equals(binding.actionType()))
                    .filter(candidate -> !candidate.consumed())
                    .filter(candidate -> candidate.status() == ApprovalRequest.ApprovalStatus.PENDING
                            || candidate.status() == ApprovalRequest.ApprovalStatus.APPROVED)
                    .findFirst().orElse(null);
            if (existing != null) return existing;
        }
        ApprovalRequest request = createBareRequest(assessment);
        if (pendingToolCall == null) {
            traceApproval(request, TraceEventType.APPROVAL_REQUESTED, "approval requested");
            return request;
        }
        ApprovalRequest updated = request.withPendingToolCall(pendingToolCall.withRequestId(request.requestId()))
                .withBinding(binding);
        save(updated);
        traceApproval(updated, TraceEventType.APPROVAL_REQUESTED, "approval requested");
        return updated;
    }

    public ApprovalRequest createRequest(RiskAssessment assessment, String toolName, Map<String, Object> arguments, String sessionId) {
        PendingToolCall pendingToolCall = PendingToolCall.create(
                null,
                toolName,
                arguments,
                sessionId,
                assessment
        );
        return createRequest(assessment, pendingToolCall);
    }

    public ApprovalRequest createChangeActionRequest(RiskAssessment assessment, PendingChangeAction pendingChangeAction) {
        return createChangeActionRequest(assessment, pendingChangeAction, ApprovalBinding.unbound());
    }

    public ApprovalRequest createChangeActionRequest(RiskAssessment assessment, PendingChangeAction pendingChangeAction,
                                                     ApprovalBinding binding) {
        ApprovalRequest existing = findBound(binding);
        if (existing != null) return existing;
        ApprovalRequest request = createBareRequest(assessment);
        if (pendingChangeAction == null) {
            traceApproval(request, TraceEventType.APPROVAL_REQUESTED, "approval requested");
            return request;
        }
        ApprovalRequest updated = request.withPendingChangeAction(pendingChangeAction.withRequestId(request.requestId()))
                .withBinding(binding);
        save(updated);
        traceApproval(updated, TraceEventType.APPROVAL_REQUESTED, "approval requested");
        return updated;
    }

    private ApprovalRequest findBound(ApprovalBinding binding) {
        if (binding == null || !binding.bound()) return null;
        return requests.values().stream()
                .filter(candidate -> candidate.binding() != null && candidate.binding().bound())
                .filter(candidate -> candidate.binding().runId().equals(binding.runId()))
                .filter(candidate -> candidate.binding().idempotencyKey().equals(binding.idempotencyKey()))
                .filter(candidate -> candidate.binding().actionType().equals(binding.actionType()))
                .filter(candidate -> !candidate.consumed())
                .filter(candidate -> candidate.status() == ApprovalRequest.ApprovalStatus.PENDING
                        || candidate.status() == ApprovalRequest.ApprovalStatus.APPROVED)
                .findFirst().orElse(null);
    }

    public ApprovalRequest findByBinding(String runId, String idempotencyKey) {
        return findByBinding(runId, idempotencyKey, null);
    }

    public ApprovalRequest findByBinding(String runId, String idempotencyKey, String actionType) {
        if (runId == null || idempotencyKey == null) return null;
        return requests.values().stream()
                .filter(candidate -> candidate.binding() != null && candidate.binding().bound())
                .filter(candidate -> candidate.binding().runId().equals(runId))
                .filter(candidate -> candidate.binding().idempotencyKey().equals(idempotencyKey))
                .filter(candidate -> actionType == null || actionType.equals(candidate.binding().actionType()))
                .findFirst().orElse(null);
    }

    private ApprovalRequest createBareRequest(RiskAssessment assessment) {
        String requestId = "approval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now(clock);
        ApprovalRequest request = ApprovalRequest.create(requestId, assessment, now, now.plus(ttl));
        save(request);
        return request;
    }

    public ApprovalRequest approve(String requestId) {
        return decide(requestId, ApprovalDecision.APPROVED);
    }

    public ApprovalRequest reject(String requestId) {
        return decide(requestId, ApprovalDecision.REJECTED);
    }

    public ApprovalRequest find(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return requests.get(requestId);
    }

    /** Updates the in-process query cache after the runtime committed a signal transaction. */
    public ApprovalRequest acceptCommitted(ApprovalRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        requests.put(request.requestId(), request);
        return request;
    }

    public List<ApprovalRequest> listPending() {
        return requests.values().stream()
                .filter(request -> request.status() == ApprovalRequest.ApprovalStatus.PENDING)
                .filter(request -> !isExpired(request))
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .toList();
    }

    public List<ApprovalRequest> list() {
        return requests.values().stream()
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .toList();
    }

    public PendingToolCall consumeApprovedToolCall(String requestId) {
        ApprovalRequest existing = claim(requestId);
        if (existing == null) {
            throw new IllegalArgumentException("审批请求不存在或已过期：" + requestId);
        }
        ensureNotExpired(existing);
        if (existing.consumed()) {
            throw new IllegalStateException("审批请求已消费，不能重复执行：" + requestId);
        }
        PendingToolCall pendingToolCall = existing.pendingToolCall();
        if (pendingToolCall == null) {
            throw new IllegalStateException("审批请求没有可恢复的工具调用：" + requestId);
        }
        if (pendingToolCall.consumed()) {
            throw new IllegalStateException("审批请求已消费，不能重复执行：" + requestId);
        }
        save(existing.markConsumed());
        return pendingToolCall;
    }

    public PendingChangeAction consumeApprovedChangeAction(String requestId) {
        ApprovalRequest existing = claim(requestId);
        if (existing == null) {
            throw new IllegalArgumentException("审批请求不存在或已过期：" + requestId);
        }
        ensureNotExpired(existing);
        if (existing.consumed()) {
            throw new IllegalStateException("审批请求已消费，不能重复执行：" + requestId);
        }
        PendingChangeAction pendingChangeAction = existing.pendingChangeAction();
        if (pendingChangeAction == null) {
            throw new IllegalStateException("审批请求没有可恢复的变更动作：" + requestId);
        }
        if (pendingChangeAction.consumed()) {
            throw new IllegalStateException("审批请求已消费，不能重复执行：" + requestId);
        }
        save(existing.markConsumed());
        return pendingChangeAction;
    }

    public ApprovalRequest claim(String requestId) {
        ApprovalRequest existing = find(requestId);
        if (existing == null) throw new IllegalArgumentException("审批请求不存在或已过期：" + requestId);
        ensureNotExpired(existing);
        if (existing.consumed()) throw new IllegalStateException("审批请求已消费，不能重复执行：" + requestId);
        if (existing.status() == ApprovalRequest.ApprovalStatus.CLAIMED) return existing;
        if (existing.status() != ApprovalRequest.ApprovalStatus.APPROVED) {
            throw new IllegalStateException("审批请求尚未批准，当前状态：" + existing.status());
        }
        return save(existing.withStatus(ApprovalRequest.ApprovalStatus.CLAIMED));
    }

    public ApprovalRequest completeClaim(String requestId) {
        ApprovalRequest existing = find(requestId);
        if (existing == null) throw new IllegalArgumentException("审批请求不存在或已过期：" + requestId);
        if (existing.consumed()) return existing;
        if (existing.status() != ApprovalRequest.ApprovalStatus.CLAIMED) {
            throw new IllegalStateException("审批请求尚未被执行节点领取，当前状态：" + existing.status());
        }
        return save(existing.markConsumed());
    }

    private ApprovalRequest decide(String requestId, ApprovalDecision decision) {
        ApprovalRequest existing = find(requestId);
        if (existing == null) {
            return null;
        }
        ensureNotExpired(existing);
        if (existing.status() != ApprovalRequest.ApprovalStatus.PENDING) {
            if (decision == ApprovalDecision.REJECTED
                    && existing.status() == ApprovalRequest.ApprovalStatus.REJECTED) {
                return existing;
            }
            if (decision == ApprovalDecision.APPROVED
                    && existing.status() == ApprovalRequest.ApprovalStatus.APPROVED
                    && (existing.pendingToolCall() != null || existing.pendingChangeAction() != null)) {
                return existing;
            }
            if (decision == ApprovalDecision.REJECTED
                    && existing.status() == ApprovalRequest.ApprovalStatus.APPROVED
                    && !existing.consumed()
                    && existing.pendingToolCall() == null
                    && existing.pendingChangeAction() == null) {
                ApprovalRequest rejected = existing.withStatus(ApprovalRequest.ApprovalStatus.REJECTED);
                save(rejected);
                traceApproval(rejected, TraceEventType.APPROVAL_REJECTED, "approval rejected");
                return rejected;
            }
            throw new IllegalStateException("审批请求已处理，当前状态：" + existing.status());
        }
        ApprovalRequest.ApprovalStatus status = decision == ApprovalDecision.APPROVED
                ? ApprovalRequest.ApprovalStatus.APPROVED
                : ApprovalRequest.ApprovalStatus.REJECTED;
        ApprovalRequest updated = existing.withStatus(status);
        if (status == ApprovalRequest.ApprovalStatus.REJECTED) {
            updated = updated.withPendingToolCall(null);
            updated = updated.withPendingChangeAction(null);
        }
        save(updated);
        traceApproval(
                updated,
                status == ApprovalRequest.ApprovalStatus.APPROVED ? TraceEventType.APPROVAL_APPROVED : TraceEventType.APPROVAL_REJECTED,
                status == ApprovalRequest.ApprovalStatus.APPROVED ? "approval approved" : "approval rejected"
        );
        return updated;
    }

    private void ensureNotExpired(ApprovalRequest request) {
        if (isExpired(request)) {
            throw new IllegalStateException("审批请求已过期：" + request.requestId() + "，expiresAt=" + request.expiresAt());
        }
    }

    private boolean isExpired(ApprovalRequest request) {
        return request != null && request.isExpired(Instant.now(clock));
    }

    private ApprovalRequest save(ApprovalRequest request) {
        requests.put(request.requestId(), request);
        store.save(request);
        return request;
    }

    private void traceApproval(ApprovalRequest request, TraceEventType type, String message) {
        if (traceStore == null || request == null) {
            return;
        }
        try {
            String sessionId = "";
            String changeSetId = "";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", request.status().name());
            if (request.riskAssessment() != null) {
                payload.put("riskLevel", request.riskAssessment().riskLevel().name());
                payload.put("toolName", request.riskAssessment().toolName());
                payload.put("reasons", request.riskAssessment().reasons());
                payload.put("affectedPaths", request.riskAssessment().affectedPaths());
            }
            if (request.pendingToolCall() != null) {
                sessionId = request.pendingToolCall().sessionId();
                payload.put("pendingToolCall", request.pendingToolCall().toolName());
            }
            if (request.pendingChangeAction() != null) {
                changeSetId = request.pendingChangeAction().changeSetId();
                payload.put("actionType", request.pendingChangeAction().actionType().name());
                payload.put("commands", request.pendingChangeAction().commands());
            }
            String traceId = traceStore.traceIdForSession(sessionId);
            TraceEvent event = new TraceEvent(
                    traceId,
                    null,
                    "",
                    sessionId,
                    "",
                    changeSetId,
                    request.requestId(),
                    type,
                    "approval",
                    message,
                    payload,
                    null,
                    null
            );
            traceStore.append(event);
        } catch (Exception ignored) {
        }
    }
}
