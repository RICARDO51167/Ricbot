package ricbot.domain.experience;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExperienceEntry(
        String id,
        ExperienceType type,
        ExperienceStatus status,
        String title,
        String content,
        String whenToApply,
        String evidence,
        String source,
        String sourceRef,
        List<String> relatedFiles,
        List<String> suggestedTests,
        double confidence,
        int successCount,
        int failureCount,
        String createdAt,
        String updatedAt
) {
    public ExperienceEntry {
        id = id != null && !id.isBlank() ? id : newId();
        type = type != null ? type : ExperienceType.PROJECT_CONVENTION;
        status = status != null ? status : ExperienceStatus.CANDIDATE;
        title = clean(title);
        content = clean(content);
        whenToApply = clean(whenToApply);
        evidence = clean(evidence);
        source = clean(source);
        sourceRef = clean(sourceRef);
        relatedFiles = relatedFiles != null ? List.copyOf(nonBlank(relatedFiles)) : List.of();
        suggestedTests = suggestedTests != null ? List.copyOf(nonBlank(suggestedTests)) : List.of();
        confidence = Math.max(0d, Math.min(1d, confidence));
        successCount = Math.max(0, successCount);
        failureCount = Math.max(0, failureCount);
        String now = Instant.now().toString();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : now;
        updatedAt = updatedAt != null && !updatedAt.isBlank() ? updatedAt : now;
    }

    public static ExperienceEntry candidate(
            ExperienceType type,
            String title,
            String content,
            String whenToApply,
            String evidence,
            String source,
            String sourceRef,
            List<String> relatedFiles,
            List<String> suggestedTests,
            double confidence
    ) {
        return new ExperienceEntry(
                null,
                type,
                ExperienceStatus.CANDIDATE,
                title,
                content,
                whenToApply,
                evidence,
                source,
                sourceRef,
                relatedFiles,
                suggestedTests,
                confidence,
                0,
                0,
                null,
                null
        );
    }

    public ExperienceEntry withStatus(ExperienceStatus nextStatus) {
        return new ExperienceEntry(
                id,
                type,
                nextStatus,
                title,
                content,
                whenToApply,
                evidence,
                source,
                sourceRef,
                relatedFiles,
                suggestedTests,
                confidence,
                successCount,
                failureCount,
                createdAt,
                Instant.now().toString()
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", type.name());
        out.put("status", status.name());
        out.put("title", title);
        out.put("content", content);
        out.put("whenToApply", whenToApply);
        out.put("evidence", evidence);
        out.put("source", source);
        out.put("sourceRef", sourceRef);
        out.put("relatedFiles", relatedFiles);
        out.put("suggestedTests", suggestedTests);
        out.put("confidence", confidence);
        out.put("successCount", successCount);
        out.put("failureCount", failureCount);
        out.put("createdAt", createdAt);
        out.put("updatedAt", updatedAt);
        return out;
    }

    public static ExperienceEntry fromMap(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        return new ExperienceEntry(
                string(raw.get("id")),
                parseType(raw.get("type")),
                parseStatus(raw.get("status")),
                string(raw.get("title")),
                string(raw.get("content")),
                string(raw.get("whenToApply")),
                string(raw.get("evidence")),
                string(raw.get("source")),
                string(raw.get("sourceRef")),
                stringList(raw.get("relatedFiles")),
                stringList(raw.get("suggestedTests")),
                number(raw.get("confidence"), 0.5d),
                integer(raw.get("successCount")),
                integer(raw.get("failureCount")),
                string(raw.get("createdAt")),
                string(raw.get("updatedAt"))
        );
    }

    private static ExperienceType parseType(Object raw) {
        try {
            return raw != null ? ExperienceType.valueOf(String.valueOf(raw)) : ExperienceType.PROJECT_CONVENTION;
        } catch (Exception e) {
            return ExperienceType.PROJECT_CONVENTION;
        }
    }

    private static ExperienceStatus parseStatus(Object raw) {
        try {
            return raw != null ? ExperienceStatus.valueOf(String.valueOf(raw)) : ExperienceStatus.CANDIDATE;
        } catch (Exception e) {
            return ExperienceStatus.CANDIDATE;
        }
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private static List<String> nonBlank(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !out.contains(value.trim())) {
                out.add(value.trim());
            }
        }
        return out;
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

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String newId() {
        return "exp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
