package ricbot.domain.security;

import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.change.PendingChangeAction;
import ricbot.domain.trace.TraceRecorder;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApprovalApplicationService {
    private final ApprovalService approvalService;
    private final ToolRegistry toolRegistry;
    private final Path workspace;
    private final TraceRecorder traceRecorder;

    public ApprovalApplicationService(ApprovalService approvalService, ToolRegistry toolRegistry, Path workspace) {
        this(approvalService, toolRegistry, workspace, TraceRecorder.forRunEvents(new ArrayList<>()));
    }

    public ApprovalApplicationService(
            ApprovalService approvalService,
            ToolRegistry toolRegistry,
            Path workspace,
            TraceRecorder traceRecorder
    ) {
        if (approvalService == null) {
            throw new IllegalArgumentException("approvalService is required");
        }
        this.approvalService = approvalService;
        this.toolRegistry = toolRegistry;
        this.workspace = workspace;
        this.traceRecorder = traceRecorder != null ? traceRecorder : TraceRecorder.forRunEvents(new ArrayList<>());
    }

    public List<ApprovalRequest> listPending() {
        List<ApprovalRequest> pending = approvalService.listPending();
        recordApprovalEvent("approval_list_pending", "", Map.of("pendingCount", pending.size()));
        return pending;
    }

    public ApprovalActionResult approveOnly(String requestId) {
        try {
            ApprovalRequest existing = requirePending(requestId);
            ApprovalRequest approved = approvalService.approve(existing.requestId());
            String message = approved.pendingToolCall() != null || approved.pendingChangeAction() != null
                    ? "approval approved but not executed"
                    : "approval approved";
            ApprovalActionResult result = ApprovalActionResult.notExecuted(approved, message);
            recordApprovalEvent("approval_approve_only", approved.requestId(), Map.of(
                    "approvalId", approved.requestId(),
                    "executed", false,
                    "executionType", executionType(approved),
                    "status", "approved",
                    "message", message
            ));
            return result;
        } catch (RuntimeException e) {
            recordApprovalError(requestId, e);
            throw e;
        }
    }

    public ApprovalActionResult approveAndExecute(String requestId) {
        try {
            ApprovalRequest existing = approvalService.find(requestId);
            if (existing == null) {
                ApprovalActionResult result = ApprovalActionResult.notFound(requestId);
                recordApprovalEvent("approval_approve_execute", requestId, Map.of(
                        "approvalId", requestId != null ? requestId : "",
                        "executed", false,
                        "executionType", "NONE",
                        "success", false,
                        "errorMessage", result.message()
                ));
                return result;
            }
            if (existing.status() != ApprovalRequest.ApprovalStatus.PENDING) {
                if (existing.consumed()) {
                    throw new IllegalStateException("审批请求已消费，不能重复执行：" + existing.requestId());
                }
                throw new IllegalStateException("审批请求已处理，当前状态：" + existing.status());
            }

            ApprovalRequest approved = approvalService.approve(existing.requestId());
            ApprovalActionResult result;
            if (approved.pendingChangeAction() != null) {
                result = executeChangeAction(approved);
            } else if (approved.pendingToolCall() != null) {
                result = executeToolCall(approved);
            } else {
                result = ApprovalActionResult.notExecuted(approved, "approval approved but no executable action is attached");
            }
            recordApproveExecute(result);
            return result;
        } catch (RuntimeException e) {
            recordApprovalError(requestId, e);
            throw e;
        }
    }

    public ApprovalActionResult reject(String requestId) {
        try {
            ApprovalRequest existing = approvalService.find(requestId);
            if (existing == null) {
                ApprovalActionResult result = ApprovalActionResult.notFound(requestId);
                recordApprovalEvent("approval_reject", requestId, Map.of(
                        "approvalId", requestId != null ? requestId : "",
                        "status", "not_found",
                        "success", false
                ));
                return result;
            }
            ApprovalRequest rejected = approvalService.reject(existing.requestId());
            ApprovalActionResult result = ApprovalActionResult.notExecuted(rejected, "approval rejected");
            recordApprovalEvent("approval_reject", rejected.requestId(), Map.of(
                    "approvalId", rejected.requestId(),
                    "status", "rejected",
                    "message", result.message()
            ));
            return result;
        } catch (RuntimeException e) {
            recordApprovalError(requestId, e);
            throw e;
        }
    }

    private ApprovalActionResult executeToolCall(ApprovalRequest approved) {
        if (toolRegistry == null) {
            return ApprovalActionResult.notExecuted(approved, "approval approved but no ToolRegistry is available for execution");
        }
        PendingToolCall pendingToolCall = approvalService.consumeApprovedToolCall(approved.requestId());
        Object result = toolRegistry.executeApproved(pendingToolCall.toolName(), pendingToolCall.arguments(), approved.requestId());
        ApprovalRequest consumed = approvalService.find(approved.requestId());
        return ApprovalActionResult.executedTool(consumed, pendingToolCall, result);
    }

    private ApprovalActionResult executeChangeAction(ApprovalRequest approved) {
        if (workspace == null) {
            return ApprovalActionResult.notExecuted(approved, "approval approved but no workspace is available for change execution");
        }
        PendingChangeAction action = approvalService.consumeApprovedChangeAction(approved.requestId());
        try {
            ChangeSetService service = new ChangeSetService(workspace);
            GitChangeSet result = switch (action.actionType()) {
                case COMMIT -> service.commit(action.changeSetId(), action.commitMessage());
                case ROLLBACK -> service.rollback(action.changeSetId());
            };
            ApprovalRequest consumed = approvalService.find(approved.requestId());
            return ApprovalActionResult.executedChange(consumed, action, result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            ApprovalRequest consumed = approvalService.find(approved.requestId());
            return ApprovalActionResult.notExecuted(consumed, "执行变更动作失败：" + e.getMessage(), action);
        }
    }

    private void recordApproveExecute(ApprovalActionResult result) {
        if (result == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("approvalId", result.requestId());
        metadata.put("executed", result.executed());
        metadata.put("executionType", result.executionType());
        metadata.put("success", result.executed());
        metadata.put("message", result.message());
        if (!result.executed()) {
            metadata.put("errorMessage", result.message());
        }
        if (result.pendingToolCall() != null) {
            metadata.put("toolName", result.pendingToolCall().toolName());
        }
        if (result.pendingChangeAction() != null) {
            metadata.put("action", result.pendingChangeAction().actionType().name());
        }
        recordApprovalEvent("approval_approve_execute", result.requestId(), metadata);
    }

    private void recordApprovalError(String requestId, RuntimeException error) {
        String errorType = approvalErrorType(error);
        recordApprovalEvent(errorType, requestId, Map.of(
                "approvalId", requestId != null ? requestId : "",
                "errorType", errorType,
                "errorMessage", error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()
        ));
    }

    private String approvalErrorType(RuntimeException error) {
        String message = error != null && error.getMessage() != null ? error.getMessage() : "";
        if (message.contains("已过期")) {
            return "approval_expired";
        }
        if (message.contains("已消费") || message.contains("当前状态：APPROVED")) {
            return "approval_repeated";
        }
        return "approval_invalid_state";
    }

    private void recordApprovalEvent(String eventType, String approvalId, Map<String, Object> metadata) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("event_type", eventType);
        if (metadata != null) {
            values.putAll(metadata);
        }
        traceRecorder.recordApprovalEvent(approvalId, values);
    }

    private String executionType(ApprovalRequest request) {
        if (request == null) {
            return "NONE";
        }
        if (request.pendingToolCall() != null) {
            return "TOOL_CALL";
        }
        if (request.pendingChangeAction() != null) {
            return "CHANGE_ACTION";
        }
        return "NONE";
    }

    private ApprovalRequest requirePending(String requestId) {
        ApprovalRequest existing = approvalService.find(requestId);
        if (existing == null) {
            throw new IllegalArgumentException("审批请求不存在或已过期：" + requestId);
        }
        if (existing.status() != ApprovalRequest.ApprovalStatus.PENDING) {
            throw new IllegalStateException("审批请求已处理，当前状态：" + existing.status());
        }
        return existing;
    }

    public record ApprovalActionResult(
            String requestId,
            ApprovalRequest request,
            boolean found,
            boolean executed,
            String executionType,
            String message,
            PendingToolCall pendingToolCall,
            PendingChangeAction pendingChangeAction,
            Object executionResult,
            GitChangeSet changeSet
    ) {
        private static ApprovalActionResult notFound(String requestId) {
            return new ApprovalActionResult(requestId, null, false, false, "NONE", "approval not found", null, null, null, null);
        }

        private static ApprovalActionResult notExecuted(ApprovalRequest request, String message) {
            return notExecuted(request, message, null);
        }

        private static ApprovalActionResult notExecuted(ApprovalRequest request, String message, PendingChangeAction pendingChangeAction) {
            return new ApprovalActionResult(
                    request != null ? request.requestId() : "",
                    request,
                    request != null,
                    false,
                    "NONE",
                    message,
                    request != null ? request.pendingToolCall() : null,
                    pendingChangeAction != null ? pendingChangeAction : request != null ? request.pendingChangeAction() : null,
                    null,
                    null
            );
        }

        private static ApprovalActionResult executedTool(ApprovalRequest request, PendingToolCall pendingToolCall, Object result) {
            return new ApprovalActionResult(request.requestId(), request, true, true, "TOOL_CALL",
                    "approval approved and tool executed", pendingToolCall, null, result, null);
        }

        private static ApprovalActionResult executedChange(ApprovalRequest request, PendingChangeAction pendingChangeAction, GitChangeSet changeSet) {
            return new ApprovalActionResult(request.requestId(), request, true, true, "CHANGE_ACTION",
                    "approval approved and change action executed", null, pendingChangeAction, changeSet, changeSet);
        }
    }
}
