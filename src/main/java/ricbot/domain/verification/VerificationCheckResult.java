package ricbot.domain.verification;

import java.time.Instant;
import java.util.List;

public record VerificationCheckResult(
        Stage stage,
        String checkId,
        String commandDigest,
        Status status,
        Integer exitCode,
        boolean timedOut,
        long durationMillis,
        String backend,
        boolean truncated,
        String outputSummary,
        String artifact,
        List<String> relatedTaskIds,
        Instant executedAt
) {
    public enum Stage { DIFF, COMPILE, TEST, ACCEPTANCE }
    public enum Status { PASS, FAIL, SKIPPED, NEEDS_HUMAN }
    public VerificationCheckResult {
        stage = stage != null ? stage : Stage.ACCEPTANCE;
        checkId = clean(checkId);
        commandDigest = clean(commandDigest);
        status = status != null ? status : Status.NEEDS_HUMAN;
        durationMillis = Math.max(0, durationMillis);
        backend = clean(backend);
        outputSummary = clean(outputSummary);
        artifact = clean(artifact);
        relatedTaskIds = relatedTaskIds != null ? List.copyOf(relatedTaskIds) : List.of();
        executedAt = executedAt != null ? executedAt : Instant.now();
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
