package ricbot.domain.security;

import ricbot.domain.change.PendingChangeAction;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ApprovalService {
    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private TraceStore traceStore;

    public ApprovalService() {
    }

    public ApprovalService(TraceStore traceStore) {
        this.traceStore = traceStore;
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
        ApprovalRequest request = createBareRequest(assessment);
        if (pendingToolCall == null) {
            traceApproval(request, TraceEventType.APPROVAL_REQUESTED, "approval requested");
            return request;
        }
        ApprovalRequest updated = request.withPendingToolCall(pendingToolCall.withRequestId(request.requestId()));
        requests.put(request.requestId(), updated);
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
        ApprovalRequest request = createBareRequest(assessment);
        if (pendingChangeAction == null) {
            traceApproval(request, TraceEventType.APPROVAL_REQUESTED, "approval requested");
            return request;
        }
        ApprovalRequest updated = request.withPendingChangeAction(pendingChangeAction.withRequestId(request.requestId()));
        requests.put(request.requestId(), updated);
        traceApproval(updated, TraceEventType.APPROVAL_REQUESTED, "approval requested");
        return updated;
    }

    private ApprovalRequest createBareRequest(RiskAssessment assessment) {
        String requestId = "approval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ApprovalRequest request = ApprovalRequest.create(requestId, assessment);
        requests.put(requestId, request);
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

    public PendingToolCall consumeApprovedToolCall(String requestId) {
        ApprovalRequest existing = find(requestId);
        if (existing == null) {
            throw new IllegalArgumentException("审批请求不存在或已过期：" + requestId);
        }
        if (existing.status() != ApprovalRequest.ApprovalStatus.APPROVED) {
            throw new IllegalStateException("审批请求尚未批准，当前状态：" + existing.status());
        }
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
        requests.put(requestId, existing.markConsumed());
        return pendingToolCall;
    }

    public PendingChangeAction consumeApprovedChangeAction(String requestId) {
        ApprovalRequest existing = find(requestId);
        if (existing == null) {
            throw new IllegalArgumentException("审批请求不存在或已过期：" + requestId);
        }
        if (existing.status() != ApprovalRequest.ApprovalStatus.APPROVED) {
            throw new IllegalStateException("审批请求尚未批准，当前状态：" + existing.status());
        }
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
        requests.put(requestId, existing.markConsumed());
        return pendingChangeAction;
    }

    private ApprovalRequest decide(String requestId, ApprovalDecision decision) {
        ApprovalRequest existing = find(requestId);
        if (existing == null) {
            return null;
        }
        ApprovalRequest.ApprovalStatus status = decision == ApprovalDecision.APPROVED
                ? ApprovalRequest.ApprovalStatus.APPROVED
                : ApprovalRequest.ApprovalStatus.REJECTED;
        ApprovalRequest updated = existing.withStatus(status);
        if (status == ApprovalRequest.ApprovalStatus.REJECTED) {
            updated = updated.withPendingToolCall(null);
            updated = updated.withPendingChangeAction(null);
        }
        requests.put(requestId, updated);
        traceApproval(
                updated,
                status == ApprovalRequest.ApprovalStatus.APPROVED ? TraceEventType.APPROVAL_APPROVED : TraceEventType.APPROVAL_REJECTED,
                status == ApprovalRequest.ApprovalStatus.APPROVED ? "approval approved" : "approval rejected"
        );
        return updated;
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
