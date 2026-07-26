package ricbot.domain.verification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record VerificationReport(
        String reportId,
        Status status,
        String workspace,
        String profileDigest,
        String diffDigest,
        List<VerificationCheckResult> checks,
        List<String> unprovenCriteria,
        String artifactDirectory,
        Instant createdAt
) {
    public enum Status { PASS, REJECT, NEEDS_HUMAN }
    public VerificationReport {
        reportId = clean(reportId);
        status = status != null ? status : Status.NEEDS_HUMAN;
        workspace = clean(workspace);
        profileDigest = clean(profileDigest);
        diffDigest = clean(diffDigest);
        checks = checks != null ? List.copyOf(checks) : List.of();
        unprovenCriteria = unprovenCriteria != null ? List.copyOf(unprovenCriteria) : List.of();
        artifactDirectory = clean(artifactDirectory);
        createdAt = createdAt != null ? createdAt : Instant.now();
    }
    public String outcome() {
        return switch (status) { case PASS -> "pass"; case REJECT -> "reject"; case NEEDS_HUMAN -> "needs_human"; };
    }
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", reportId); out.put("status", status.name()); out.put("workspace", workspace);
        out.put("profileDigest", profileDigest); out.put("diffDigest", diffDigest); out.put("checks", checks);
        out.put("unprovenCriteria", unprovenCriteria); out.put("artifactDirectory", artifactDirectory);
        out.put("createdAt", createdAt.toString());
        return Map.copyOf(out);
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
