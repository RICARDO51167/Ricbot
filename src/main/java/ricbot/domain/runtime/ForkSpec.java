package ricbot.domain.runtime;

import java.util.UUID;

public record ForkSpec(String sourceRunId, long throughCommit, String newRunId, boolean execute) {
    public ForkSpec {
        sourceRunId = required(sourceRunId, "sourceRunId");
        if (throughCommit < 0) throw new IllegalArgumentException("throughCommit cannot be negative");
        newRunId = clean(newRunId).isBlank() ? "run-" + UUID.randomUUID() : clean(newRunId);
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
