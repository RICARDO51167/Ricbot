package ricbot.infra.execution;

import java.time.Duration;
import java.util.Map;

public record ExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean truncated,
        Duration duration,
        String backend,
        Map<String, Object> metadata
) {
    public ExecutionResult {
        stdout = stdout != null ? stdout : "";
        stderr = stderr != null ? stderr : "";
        duration = duration != null ? duration : Duration.ZERO;
        backend = backend != null ? backend : "unknown";
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
