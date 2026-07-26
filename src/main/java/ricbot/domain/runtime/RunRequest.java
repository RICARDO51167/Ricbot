package ricbot.domain.runtime;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record RunRequest(
        String runId,
        String sessionId,
        Mode mode,
        String goal,
        Path workspace,
        int maxSupersteps,
        Map<String, Object> metadata
) {
    public enum Mode { AGENT, TEAM }

    public RunRequest {
        runId = clean(runId).isBlank() ? "run-" + UUID.randomUUID() : clean(runId);
        sessionId = clean(sessionId);
        mode = mode != null ? mode : Mode.AGENT;
        goal = required(goal, "goal");
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        workspace = workspace.toAbsolutePath().normalize();
        if (maxSupersteps < 1) maxSupersteps = 128;
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata != null ? metadata : Map.of()));
    }
    private static String required(String value, String field) {
        String result = clean(value); if (result.isBlank()) throw new IllegalArgumentException(field + " is required"); return result;
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
