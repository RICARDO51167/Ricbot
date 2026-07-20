package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PendingImplementationStep(
        String id,
        String teamSessionId,
        String taskId,
        TeamRole role,
        ImplementationStepType type,
        String targetPath,
        String command,
        String oldText,
        String newText,
        String reason,
        CommandRiskLevel riskLevel,
        boolean requiresApproval,
        Map<String, Object> policyDecision,
        ImplementationStepStatus status,
        int orderIndex,
        List<String> dependsOnStepIds,
        List<String> unblocksStepIds,
        List<String> blockedBy,
        String blockedReason,
        String qualityGate,
        List<String> requiredBeforeApply,
        String lastUpdatedBy,
        String updateReason,
        List<String> validationErrors,
        String createdAt,
        String updatedAt
) {
    public PendingImplementationStep {
        id = id != null && !id.isBlank() ? id : newId();
        teamSessionId = clean(teamSessionId);
        taskId = clean(taskId);
        role = role != null ? role : TeamRole.DEVELOPER;
        type = type != null ? type : ImplementationStepType.READ;
        targetPath = clean(targetPath);
        command = clean(command);
        oldText = oldText != null ? oldText : "";
        newText = newText != null ? newText : "";
        reason = clean(reason);
        riskLevel = riskLevel != null ? riskLevel : CommandRiskLevel.SAFE;
        policyDecision = policyDecision != null ? Map.copyOf(policyDecision) : Map.of();
        status = status != null ? status : ImplementationStepStatus.DRAFT;
        dependsOnStepIds = copy(dependsOnStepIds);
        unblocksStepIds = copy(unblocksStepIds);
        blockedBy = copy(blockedBy);
        blockedReason = clean(blockedReason);
        qualityGate = clean(qualityGate);
        requiredBeforeApply = copy(requiredBeforeApply);
        lastUpdatedBy = clean(lastUpdatedBy);
        updateReason = clean(updateReason);
        validationErrors = copy(validationErrors);
        String now = Instant.now().toString();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : now;
        updatedAt = updatedAt != null && !updatedAt.isBlank() ? updatedAt : now;
    }

    public PendingImplementationStep(
            String id,
            String teamSessionId,
            String taskId,
            TeamRole role,
            ImplementationStepType type,
            String targetPath,
            String command,
            String oldText,
            String newText,
            String reason,
            CommandRiskLevel riskLevel,
            boolean requiresApproval,
            Map<String, Object> policyDecision,
            ImplementationStepStatus status,
            String createdAt,
            String updatedAt
    ) {
        this(id, teamSessionId, taskId, role, type, targetPath, command, oldText, newText, reason,
                riskLevel, requiresApproval, policyDecision, status, 0, List.of(), List.of(), List.of(),
                "", "", List.of(), "", "", List.of(), createdAt, updatedAt);
    }

    public PendingImplementationStep withStatus(ImplementationStepStatus nextStatus) {
        return new PendingImplementationStep(id, teamSessionId, taskId, role, type, targetPath, command, oldText,
                newText, reason, riskLevel, requiresApproval, policyDecision, nextStatus, orderIndex,
                dependsOnStepIds, unblocksStepIds, blockedBy, blockedReason, qualityGate, requiredBeforeApply,
                lastUpdatedBy, updateReason, validationErrors, createdAt, Instant.now().toString());
    }

    public PendingImplementationStep withPolicyDecision(Map<String, Object> decision, ImplementationStepStatus nextStatus) {
        return new PendingImplementationStep(id, teamSessionId, taskId, role, type, targetPath, command, oldText,
                newText, reason, riskLevel, requiresApproval, decision, nextStatus, orderIndex,
                dependsOnStepIds, unblocksStepIds, blockedBy, blockedReason, qualityGate, requiredBeforeApply,
                lastUpdatedBy, updateReason, validationErrors, createdAt, Instant.now().toString());
    }

    public PendingImplementationStep withDependencies(int nextOrderIndex, List<String> dependsOn, List<String> unblocks, String nextQualityGate, List<String> required) {
        return new PendingImplementationStep(id, teamSessionId, taskId, role, type, targetPath, command, oldText,
                newText, reason, riskLevel, requiresApproval, policyDecision, status, nextOrderIndex,
                dependsOn, unblocks, blockedBy, blockedReason, nextQualityGate, required,
                lastUpdatedBy, updateReason, validationErrors, createdAt, Instant.now().toString());
    }

    public PendingImplementationStep withGateResult(StepGateResult gateResult, ImplementationStepStatus nextStatus) {
        StepGateResult safe = gateResult != null ? gateResult : StepGateResult.allow();
        return new PendingImplementationStep(id, teamSessionId, taskId, role, type, targetPath, command, oldText,
                newText, reason, riskLevel, requiresApproval, policyDecision, nextStatus, orderIndex,
                dependsOnStepIds, unblocksStepIds, safe.blockedBy(), String.join("; ", safe.reasons()),
                qualityGate, safe.requiredActions(), lastUpdatedBy, updateReason, validationErrors,
                createdAt, Instant.now().toString());
    }

    public PendingImplementationStep withUpdate(StepUpdateRequest request, String updatedBy) {
        StepUpdateRequest safe = request != null ? request : StepUpdateRequest.empty();
        String nextCommand = !safe.command().isBlank()
                ? safe.command()
                : (command.isBlank() && type == ImplementationStepType.EXEC_TEST && !safe.suggestedTests().isEmpty() ? safe.suggestedTests().get(0) : command);
        return new PendingImplementationStep(id, teamSessionId, taskId, role, type,
                !safe.targetPath().isBlank() ? safe.targetPath() : targetPath,
                nextCommand,
                safe.oldText() != null ? safe.oldText() : oldText,
                safe.newText() != null ? safe.newText() : newText,
                !safe.reason().isBlank() ? safe.reason() : reason,
                riskLevel,
                requiresApproval,
                policyDecision,
                status,
                orderIndex,
                dependsOnStepIds,
                unblocksStepIds,
                List.of(),
                "",
                qualityGate,
                requiredBeforeApply,
                updatedBy,
                safe.updateReason(),
                List.of(),
                createdAt,
                Instant.now().toString());
    }

    public PendingImplementationStep withValidation(List<String> errors, ImplementationStepStatus nextStatus) {
        return new PendingImplementationStep(id, teamSessionId, taskId, role, type, targetPath, command, oldText,
                newText, reason, riskLevel, requiresApproval, policyDecision, nextStatus, orderIndex,
                dependsOnStepIds, unblocksStepIds, nextStatus == ImplementationStepStatus.BLOCKED ? blockedBy : List.of(),
                nextStatus == ImplementationStepStatus.BLOCKED ? blockedReason : "",
                qualityGate, requiredBeforeApply, lastUpdatedBy, updateReason, errors,
                createdAt, Instant.now().toString());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("teamSessionId", teamSessionId);
        out.put("taskId", taskId);
        out.put("role", role.name());
        out.put("type", type.name());
        out.put("targetPath", targetPath);
        out.put("command", command);
        out.put("oldText", oldText);
        out.put("newText", newText);
        out.put("reason", reason);
        out.put("riskLevel", riskLevel.name());
        out.put("requiresApproval", requiresApproval);
        out.put("policyDecision", policyDecision);
        out.put("status", status.name());
        out.put("orderIndex", orderIndex);
        out.put("dependsOnStepIds", dependsOnStepIds);
        out.put("unblocksStepIds", unblocksStepIds);
        out.put("blockedBy", blockedBy);
        out.put("blockedReason", blockedReason);
        out.put("qualityGate", qualityGate);
        out.put("requiredBeforeApply", requiredBeforeApply);
        out.put("lastUpdatedBy", lastUpdatedBy);
        out.put("updateReason", updateReason);
        out.put("validationErrors", validationErrors);
        out.put("createdAt", createdAt);
        out.put("updatedAt", updatedAt);
        return out;
    }

    public static PendingImplementationStep fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new PendingImplementationStep(
                string(raw.get("id")),
                string(raw.get("teamSessionId")),
                string(raw.get("taskId")),
                parseRole(raw.get("role")),
                parseType(raw.get("type")),
                string(raw.get("targetPath")),
                string(raw.get("command")),
                string(raw.get("oldText")),
                string(raw.get("newText")),
                string(raw.get("reason")),
                parseRisk(raw.get("riskLevel")),
                bool(raw.get("requiresApproval")),
                map(raw.get("policyDecision")),
                parseStatus(raw.get("status")),
                integer(raw.get("orderIndex")),
                stringList(raw.get("dependsOnStepIds")),
                stringList(raw.get("unblocksStepIds")),
                stringList(raw.get("blockedBy")),
                string(raw.get("blockedReason")),
                string(raw.get("qualityGate")),
                stringList(raw.get("requiredBeforeApply")),
                string(raw.get("lastUpdatedBy")),
                string(raw.get("updateReason")),
                stringList(raw.get("validationErrors")),
                string(raw.get("createdAt")),
                string(raw.get("updatedAt"))
        );
    }

    private static TeamRole parseRole(Object raw) {
        try {
            return raw != null ? TeamRole.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : TeamRole.DEVELOPER;
        } catch (Exception e) {
            return TeamRole.DEVELOPER;
        }
    }

    private static ImplementationStepType parseType(Object raw) {
        try {
            return raw != null ? ImplementationStepType.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : ImplementationStepType.READ;
        } catch (Exception e) {
            return ImplementationStepType.READ;
        }
    }

    private static ImplementationStepStatus parseStatus(Object raw) {
        try {
            return raw != null ? ImplementationStepStatus.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : ImplementationStepStatus.DRAFT;
        } catch (Exception e) {
            return ImplementationStepStatus.DRAFT;
        }
    }

    private static CommandRiskLevel parseRisk(Object raw) {
        try {
            return raw != null ? CommandRiskLevel.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : CommandRiskLevel.SAFE;
        } catch (Exception e) {
            return CommandRiskLevel.SAFE;
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

    private static boolean bool(Object raw) {
        return raw instanceof Boolean b ? b : raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    private static int integer(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return raw != null ? Integer.parseInt(String.valueOf(raw)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String value = string(item).trim();
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
        } else {
            String value = string(raw).trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<String> copy(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values != null ? values : List.<String>of()) {
            String cleaned = clean(value);
            if (!cleaned.isBlank() && !out.contains(cleaned)) {
                out.add(cleaned);
            }
        }
        return List.copyOf(out);
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String newId() {
        return "implstep_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
