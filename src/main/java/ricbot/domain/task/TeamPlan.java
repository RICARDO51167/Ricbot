package ricbot.domain.task;

import java.util.Comparator;
import java.util.List;

/** JSON-schema-friendly leader output. */
public record TeamPlan(String planId, String parentRunId, int revision, List<TaskSpec> tasks) {
    public TeamPlan {
        planId = required(planId, "planId");
        parentRunId = required(parentRunId, "parentRunId");
        if (revision < 0 || revision > 2) throw new IllegalArgumentException("revision must be between 0 and 2");
        tasks = (tasks != null ? tasks : List.<TaskSpec>of()).stream()
                .sorted(Comparator.comparingInt(TaskSpec::planOrder).thenComparing(TaskSpec::taskId)).toList();
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
