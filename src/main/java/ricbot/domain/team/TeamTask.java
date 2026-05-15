package ricbot.domain.team;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TeamTask(
        String id,
        String sessionId,
        TeamRole role,
        String goal,
        TeamTaskState state,
        String summary,
        List<TeamArtifact> artifacts,
        VerificationResult verificationResult,
        String revisionRequest,
        String createdAt,
        String updatedAt
) {
    public TeamTask {
        id = id != null && !id.isBlank() ? id : newId();
        sessionId = clean(sessionId);
        role = role != null ? role : TeamRole.DEVELOPER;
        goal = clean(goal);
        state = state != null ? state : TeamTaskState.CREATED;
        summary = clean(summary);
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        revisionRequest = clean(revisionRequest);
        String now = Instant.now().toString();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : now;
        updatedAt = updatedAt != null && !updatedAt.isBlank() ? updatedAt : now;
    }

    public TeamTask withState(TeamTaskState next) {
        return new TeamTask(id, sessionId, role, goal, next, summary, artifacts, verificationResult, revisionRequest, createdAt, Instant.now().toString());
    }

    public TeamTask withWorkerResult(String nextSummary, List<TeamArtifact> nextArtifacts) {
        return new TeamTask(
                id,
                sessionId,
                role,
                goal,
                state,
                nextSummary,
                nextArtifacts != null ? nextArtifacts : List.of(),
                verificationResult,
                revisionRequest,
                createdAt,
                Instant.now().toString()
        );
    }

    public TeamTask withVerification(VerificationResult result, TeamTaskState nextState, String nextRevisionRequest) {
        return new TeamTask(
                id,
                sessionId,
                role,
                goal,
                nextState,
                summary,
                artifacts,
                result,
                nextRevisionRequest,
                createdAt,
                Instant.now().toString()
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("sessionId", sessionId);
        out.put("role", role.name());
        out.put("goal", goal);
        out.put("state", state.name());
        out.put("summary", summary);
        out.put("artifacts", artifacts.stream().map(TeamArtifact::toMap).toList());
        out.put("verificationResult", verificationResult != null ? verificationResult.toMap() : null);
        out.put("revisionRequest", revisionRequest);
        out.put("createdAt", createdAt);
        out.put("updatedAt", updatedAt);
        return out;
    }

    public static TeamTask fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new TeamTask(
                string(raw.get("id")),
                string(raw.get("sessionId")),
                parseRole(raw.get("role")),
                string(raw.get("goal")),
                parseState(raw.get("state")),
                string(raw.get("summary")),
                artifacts(raw.get("artifacts")),
                raw.get("verificationResult") instanceof Map<?, ?> verification ? VerificationResult.fromMap(verification) : null,
                string(raw.get("revisionRequest")),
                string(raw.get("createdAt")),
                string(raw.get("updatedAt"))
        );
    }

    private static List<TeamArtifact> artifacts(Object raw) {
        List<TeamArtifact> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    TeamArtifact artifact = TeamArtifact.fromMap(map);
                    if (artifact != null) {
                        out.add(artifact);
                    }
                }
            }
        }
        return out;
    }

    private static TeamRole parseRole(Object raw) {
        try {
            return raw != null ? TeamRole.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : TeamRole.DEVELOPER;
        } catch (Exception e) {
            return TeamRole.DEVELOPER;
        }
    }

    private static TeamTaskState parseState(Object raw) {
        try {
            return raw != null ? TeamTaskState.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : TeamTaskState.CREATED;
        } catch (Exception e) {
            return TeamTaskState.CREATED;
        }
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String newId() {
        return "teamtask_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
