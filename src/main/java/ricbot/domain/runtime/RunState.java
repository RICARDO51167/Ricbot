package ricbot.domain.runtime;

import java.util.List;
import java.util.Map;

/** Recovery state only; transcript bodies, memory and artifact bytes live behind references. */
public record RunState(int schemaVersion, String graphVersion, RunSpec spec, RunStatus status,
                       RuntimePhase phase, long superstep, long commitSequence,
                       Map<String, Object> channels, WaitReason waitReason,
                       boolean cancelRequested, String failureCode, String failureMessage,
                       List<String> childRunIds, List<String> artifactReferences) {
    public static final int SCHEMA_VERSION = 3;
    public static final String GRAPH_VERSION = "ricbot-durable-runtime-v6";

    public RunState {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported run schema: " + schemaVersion);
        if (!GRAPH_VERSION.equals(graphVersion)) throw new IllegalArgumentException("unsupported graph version: " + graphVersion);
        if (spec == null) throw new IllegalArgumentException("spec is required");
        if (status == null || phase == null) throw new IllegalArgumentException("status and phase are required");
        if (superstep < 0 || commitSequence < 0) throw new IllegalArgumentException("sequences cannot be negative");
        channels = Map.copyOf(channels != null ? channels : Map.of());
        failureCode = clean(failureCode); failureMessage = clean(failureMessage);
        childRunIds = List.copyOf(childRunIds != null ? childRunIds : List.of());
        artifactReferences = List.copyOf(artifactReferences != null ? artifactReferences : List.of());
        if (status == RunStatus.WAITING && waitReason == null) throw new IllegalArgumentException("WAITING requires a reason");
        if (status != RunStatus.WAITING && waitReason != null) throw new IllegalArgumentException("only WAITING may carry a reason");
        if (status.terminal() && phase != RuntimePhase.TERMINAL) throw new IllegalArgumentException("terminal status requires TERMINAL phase");
    }

    public static RunState initial(RunSpec spec) {
        return new RunState(SCHEMA_VERSION, GRAPH_VERSION, spec, RunStatus.READY, RuntimePhase.INGEST,
                0, 0, Map.of("goal", spec.goal(), "metadata", spec.metadata()), null,
                false, "", "", List.of(), List.of());
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
