package ricbot.domain.team;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record StepAuditRecord(
        String id,
        String stepId,
        String taskId,
        String teamSessionId,
        StepAuditEventType eventType,
        String beforeStatus,
        String afterStatus,
        String message,
        String approvalRequestId,
        String toolName,
        String toolResultSummary,
        String changeSetId,
        String verificationStatus,
        String traceEventId,
        String createdAt,
        Map<String, Object> metadata
) {
    public StepAuditRecord {
        id = id != null && !id.isBlank() ? id : newId();
        stepId = clean(stepId);
        taskId = clean(taskId);
        teamSessionId = clean(teamSessionId);
        eventType = eventType != null ? eventType : StepAuditEventType.STEP_UPDATED;
        beforeStatus = clean(beforeStatus);
        afterStatus = clean(afterStatus);
        message = clean(message);
        approvalRequestId = clean(approvalRequestId);
        toolName = clean(toolName);
        toolResultSummary = clean(toolResultSummary);
        changeSetId = clean(changeSetId);
        verificationStatus = clean(verificationStatus);
        traceEventId = clean(traceEventId);
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static StepAuditRecord of(PendingImplementationStep step, StepAuditEventType eventType, String beforeStatus, String message) {
        return new StepAuditRecord(null,
                step != null ? step.id() : "",
                step != null ? step.taskId() : "",
                step != null ? step.teamSessionId() : "",
                eventType,
                beforeStatus,
                step != null && step.status() != null ? step.status().name() : "",
                message,
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                step != null ? step.toMap() : Map.of());
    }

    public StepAuditRecord withTraceEventId(String nextTraceEventId) {
        return new StepAuditRecord(id, stepId, taskId, teamSessionId, eventType, beforeStatus, afterStatus,
                message, approvalRequestId, toolName, toolResultSummary, changeSetId, verificationStatus,
                nextTraceEventId, createdAt, metadata);
    }

    public StepAuditRecord withStepLink(String nextStepId, String confidence) {
        Map<String, Object> nextMetadata = new LinkedHashMap<>(metadata);
        if (confidence != null && !confidence.isBlank()) {
            nextMetadata.put("linkConfidence", confidence);
        }
        return new StepAuditRecord(id, nextStepId, taskId, teamSessionId, eventType, beforeStatus, afterStatus,
                message, approvalRequestId, toolName, toolResultSummary, changeSetId, verificationStatus,
                traceEventId, createdAt, nextMetadata);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("stepId", stepId);
        out.put("taskId", taskId);
        out.put("teamSessionId", teamSessionId);
        out.put("eventType", eventType.name());
        out.put("beforeStatus", beforeStatus);
        out.put("afterStatus", afterStatus);
        out.put("message", message);
        out.put("approvalRequestId", approvalRequestId);
        out.put("toolName", toolName);
        out.put("toolResultSummary", toolResultSummary);
        out.put("changeSetId", changeSetId);
        out.put("verificationStatus", verificationStatus);
        out.put("traceEventId", traceEventId);
        out.put("createdAt", createdAt);
        out.put("metadata", metadata);
        return out;
    }

    public static StepAuditRecord fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new StepAuditRecord(
                string(raw.get("id")),
                string(raw.get("stepId")),
                string(raw.get("taskId")),
                string(raw.get("teamSessionId")),
                parseEventType(raw.get("eventType")),
                string(raw.get("beforeStatus")),
                string(raw.get("afterStatus")),
                string(raw.get("message")),
                string(raw.get("approvalRequestId")),
                string(raw.get("toolName")),
                string(raw.get("toolResultSummary")),
                string(raw.get("changeSetId")),
                string(raw.get("verificationStatus")),
                string(raw.get("traceEventId")),
                string(raw.get("createdAt")),
                map(raw.get("metadata"))
        );
    }

    private static StepAuditEventType parseEventType(Object raw) {
        try {
            return raw != null ? StepAuditEventType.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : StepAuditEventType.STEP_UPDATED;
        } catch (Exception e) {
            return StepAuditEventType.STEP_UPDATED;
        }
    }

    private static Map<String, Object> map(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return out;
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String newId() {
        return "stepaudit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
