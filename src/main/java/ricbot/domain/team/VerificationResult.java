package ricbot.domain.team;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record VerificationResult(
        Status status,
        String reason,
        String summary,
        List<String> suggestedTests,
        double confidence,
        String createdAt
) {
    public enum Status {
        PASS,
        REJECT,
        NEEDS_HUMAN
    }

    public VerificationResult {
        status = status != null ? status : Status.NEEDS_HUMAN;
        reason = clean(reason);
        summary = clean(summary);
        suggestedTests = suggestedTests != null ? List.copyOf(nonBlank(suggestedTests)) : List.of();
        confidence = Math.max(0d, Math.min(1d, confidence));
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
    }

    public static VerificationResult pass(String reason) {
        return new VerificationResult(Status.PASS, reason, "Verifier accepted the worker result.", List.of(), 0.75d, null);
    }

    public static VerificationResult reject(String reason) {
        return new VerificationResult(Status.REJECT, reason, "Verifier rejected the worker result and requested revision.", List.of(), 0.75d, null);
    }

    public static VerificationResult needsHuman(String reason) {
        return new VerificationResult(Status.NEEDS_HUMAN, reason, "Verifier needs human input before continuing.", List.of(), 0.65d, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status.name());
        out.put("reason", reason);
        out.put("summary", summary);
        out.put("suggestedTests", suggestedTests);
        out.put("confidence", confidence);
        out.put("createdAt", createdAt);
        return out;
    }

    public static VerificationResult fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new VerificationResult(
                parseStatus(raw.get("status")),
                string(raw.get("reason")),
                string(raw.get("summary")),
                stringList(raw.get("suggestedTests")),
                number(raw.get("confidence"), 0.5d),
                string(raw.get("createdAt"))
        );
    }

    private static Status parseStatus(Object raw) {
        try {
            return raw != null ? Status.valueOf(String.valueOf(raw).replace("-", "_").toUpperCase(java.util.Locale.ROOT)) : Status.NEEDS_HUMAN;
        } catch (Exception e) {
            return Status.NEEDS_HUMAN;
        }
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
}
