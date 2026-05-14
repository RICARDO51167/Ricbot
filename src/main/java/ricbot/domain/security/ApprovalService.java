package ricbot.domain.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ApprovalService {
    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();

    public ApprovalRequest createRequest(RiskAssessment assessment) {
        String requestId = "approval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ApprovalRequest request = ApprovalRequest.create(requestId, assessment);
        requests.put(requestId, request);
        return request;
    }

    public ApprovalRequest createRequest(RiskAssessment assessment, PendingToolCall pendingToolCall) {
        ApprovalRequest request = createRequest(assessment);
        if (pendingToolCall == null) {
            return request;
        }
        ApprovalRequest updated = request.withPendingToolCall(pendingToolCall.withRequestId(request.requestId()));
        requests.put(request.requestId(), updated);
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
        }
        requests.put(requestId, updated);
        return updated;
    }
}
