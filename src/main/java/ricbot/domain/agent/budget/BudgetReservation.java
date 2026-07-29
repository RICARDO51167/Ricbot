package ricbot.domain.agent.budget;

import java.time.Instant;

public record BudgetReservation(String reservationId, String rootRunId, String runId, String taskId,
                                long tokens, long costMicrousd, long toolCalls, long activeMillis,
                                Instant createdAt, boolean settled) {
    public BudgetReservation {
        if (reservationId == null || reservationId.isBlank()) throw new IllegalArgumentException("reservationId is required");
        rootRunId = clean(rootRunId);
        runId = clean(runId);
        taskId = clean(taskId);
        createdAt = createdAt != null ? createdAt : Instant.now();
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
