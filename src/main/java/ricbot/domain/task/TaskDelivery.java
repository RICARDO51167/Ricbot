package ricbot.domain.task;

import java.time.Instant;
import java.util.Objects;

public record TaskDelivery(String deliveryId, String parentRunId, String taskId, TaskResult result,
                           Instant deliveredAt, boolean acknowledged) {
    public TaskDelivery {
        deliveryId = required(deliveryId, "deliveryId");
        parentRunId = required(parentRunId, "parentRunId");
        taskId = required(taskId, "taskId");
        result = Objects.requireNonNull(result, "result");
        deliveredAt = Objects.requireNonNullElseGet(deliveredAt, Instant::now);
    }
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
