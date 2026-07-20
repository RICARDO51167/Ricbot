package ricbot.infra.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Docker backend with a workspace-only mount and network disabled by default. */
public final class DockerExecutionBackend implements ExecutionBackend {
    private static final Pattern IMAGE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._/:@-]{0,255}");
    private final String image;
    private final boolean networkEnabled;
    private final LocalExecutionBackend local = new LocalExecutionBackend();

    public DockerExecutionBackend(String image) {
        this(image, false);
    }

    public DockerExecutionBackend(String image, boolean networkEnabled) {
        String clean = image != null ? image.trim() : "";
        if (!IMAGE.matcher(clean).matches()) throw new IllegalArgumentException("invalid Docker image reference");
        this.image = clean;
        this.networkEnabled = networkEnabled;
    }

    public String name() { return "docker"; }

    public ExecutionCapabilities probe() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            String detail = finished ? new String(process.getInputStream().readAllBytes()).trim() : "probe timed out";
            if (!finished) process.destroyForcibly();
            return new ExecutionCapabilities(finished && process.exitValue() == 0,
                    true, !networkEnabled, false, detail);
        } catch (Exception e) {
            return new ExecutionCapabilities(false, true, !networkEnabled, false, e.getMessage());
        }
    }

    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        ExecutionCapabilities capabilities = probe();
        if (!capabilities.available()) {
            throw new IllegalStateException("Docker backend unavailable: " + capabilities.detail());
        }
        String mount = request.workingDirectory() + ":/workspace:rw";
        StringBuilder command = new StringBuilder("docker run --rm ");
        if (!networkEnabled) command.append("--network none ");
        command.append("-v ").append(shell(mount)).append(" -w /workspace ");
        request.environment().forEach((key, value) -> {
            if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                command.append("-e ").append(shell(key + "=" + value)).append(' ');
            }
        });
        command.append(shell(image)).append(" /bin/sh -lc ").append(shell(request.command()));
        ExecutionResult result = local.execute(new ExecutionRequest(
                command.toString(), request.workingDirectory(), minimalHostEnvironment(),
                request.timeout(), request.maxCaptureBytes()));
        return new ExecutionResult(result.exitCode(), result.stdout(), result.stderr(), result.timedOut(),
                result.truncated(), result.duration(), name(), Map.of(
                        "image", image,
                        "network_enabled", networkEnabled,
                        "isolation", "container"
                ));
    }

    private static Map<String, String> minimalHostEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("HOME", System.getenv().getOrDefault("HOME", ""));
        return env;
    }

    private static String shell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
