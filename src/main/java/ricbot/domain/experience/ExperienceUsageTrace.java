package ricbot.domain.experience;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ExperienceUsageTrace(
        String id,
        String experienceId,
        String sessionId,
        String taskGoal,
        String query,
        double relevanceScore,
        String usedAt,
        ExperienceOutcome outcome,
        String evidence
) {
    public ExperienceUsageTrace {
        id = id != null && !id.isBlank() ? id : newId();
        experienceId = clean(experienceId);
        sessionId = clean(sessionId);
        taskGoal = clean(taskGoal);
        query = clean(query);
        relevanceScore = Math.max(0d, Math.min(1d, relevanceScore));
        usedAt = usedAt != null && !usedAt.isBlank() ? usedAt : Instant.now().toString();
        outcome = outcome != null ? outcome : ExperienceOutcome.UNKNOWN;
        evidence = clean(evidence);
    }

    static ExperienceUsageTrace unknown(
            String experienceId,
            String sessionId,
            String taskGoal,
            String query,
            double relevanceScore,
            String evidence
    ) {
        return new ExperienceUsageTrace(
                null,
                experienceId,
                sessionId,
                taskGoal,
                query,
                relevanceScore,
                null,
                ExperienceOutcome.UNKNOWN,
                evidence
        );
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("experienceId", experienceId);
        out.put("sessionId", sessionId);
        out.put("taskGoal", taskGoal);
        out.put("query", query);
        out.put("relevanceScore", relevanceScore);
        out.put("usedAt", usedAt);
        out.put("outcome", outcome.name());
        out.put("evidence", evidence);
        return out;
    }

    static ExperienceUsageTrace fromMap(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        return new ExperienceUsageTrace(
                string(raw.get("id")),
                string(raw.get("experienceId")),
                string(raw.get("sessionId")),
                string(raw.get("taskGoal")),
                string(raw.get("query")),
                number(raw.get("relevanceScore"), 0d),
                string(raw.get("usedAt")),
                parseOutcome(raw.get("outcome")),
                string(raw.get("evidence"))
        );
    }

    ExperienceUsageTrace withOutcome(ExperienceOutcome nextOutcome, String nextEvidence) {
        return new ExperienceUsageTrace(
                id,
                experienceId,
                sessionId,
                taskGoal,
                query,
                relevanceScore,
                usedAt,
                nextOutcome,
                nextEvidence != null && !nextEvidence.isBlank() ? nextEvidence : evidence
        );
    }

    private static ExperienceOutcome parseOutcome(Object raw) {
        try {
            return raw != null ? ExperienceOutcome.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : ExperienceOutcome.UNKNOWN;
        } catch (Exception e) {
            return ExperienceOutcome.UNKNOWN;
        }
    }

    private static double number(Object raw, double fallback) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return raw != null ? Double.parseDouble(String.valueOf(raw)) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String newId() {
        return "usage_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
