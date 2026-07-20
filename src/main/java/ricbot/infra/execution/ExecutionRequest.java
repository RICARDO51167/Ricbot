package ricbot.infra.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public record ExecutionRequest(
        String command,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        int maxCaptureBytes
) {
    public ExecutionRequest {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command is required");
        if (workingDirectory == null) throw new IllegalArgumentException("workingDirectory is required");
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
        environment = Map.copyOf(new LinkedHashMap<>(environment != null ? environment : Map.of()));
        timeout = timeout != null && !timeout.isNegative() && !timeout.isZero() ? timeout : Duration.ofSeconds(60);
        maxCaptureBytes = Math.max(1024, maxCaptureBytes);
    }
}
