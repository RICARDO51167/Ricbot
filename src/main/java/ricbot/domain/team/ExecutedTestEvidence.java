package ricbot.domain.team;

import java.time.Instant;

public record ExecutedTestEvidence(
        String command,
        Integer exitCode,
        boolean passed,
        String outputSummary,
        Long durationMillis,
        Instant executedAt
) {
    public ExecutedTestEvidence {
        command = clean(command);
        outputSummary = clean(outputSummary);
        if (durationMillis != null && durationMillis < 0) {
            durationMillis = 0L;
        }
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
