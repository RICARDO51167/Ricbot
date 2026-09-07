package ricbot.domain.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunSpec(String runId, String parentRunId, String rootRunId, String retryOfRunId,
                      List<String> dependencies, String goal, String executionPolicyRef,
                      int maxSupersteps, Map<String, Object> metadata) {
    public RunSpec {
        runId = clean(runId).isBlank() ? "run-" + UUID.randomUUID() : clean(runId);
        parentRunId = clean(parentRunId);
        rootRunId = clean(rootRunId).isBlank() ? (parentRunId.isBlank() ? runId : parentRunId) : clean(rootRunId);
        retryOfRunId = clean(retryOfRunId);
        dependencies = List.copyOf(dependencies != null ? dependencies : List.of());
        goal = required(goal, "goal");
        executionPolicyRef = clean(executionPolicyRef);
        if (maxSupersteps < 1) maxSupersteps = 128;
        metadata = Map.copyOf(metadata != null ? metadata : Map.of());
    }

    public static RunSpec root(String goal) {
        return new RunSpec("", "", "", "", List.of(), goal, "", 128, Map.of());
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
