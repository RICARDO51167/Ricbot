package ricbot.infra.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Docker backend with a workspace-only mount and network disabled by default. */
public final class DockerExecutionBackend implements ExecutionBackend {
    private static final Pattern IMAGE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._/:@-]{0,255}");
    private final String image;
    private final boolean networkEnabled;
    private final DockerHost host;

    public DockerExecutionBackend(String image) {
        this(image, false);
    }

    public DockerExecutionBackend(String image, boolean networkEnabled) {
        this(image, networkEnabled, new LocalDockerHost());
    }

    DockerExecutionBackend(String image, boolean networkEnabled, DockerHost host) {
        String clean = image != null ? image.trim() : "";
        if (!IMAGE.matcher(clean).matches()) throw new IllegalArgumentException("invalid Docker image reference");
        this.image = clean;
        this.networkEnabled = networkEnabled;
        this.host = java.util.Objects.requireNonNull(host, "host");
    }

    public String name() { return "docker"; }

    public ExecutionCapabilities probe() {
        return host.probe(networkEnabled);
    }

    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        ExecutionCapabilities capabilities = probe();
        if (!capabilities.available()) {
            throw new IllegalStateException("Docker backend unavailable: " + capabilities.detail());
        }
        String containerName = "ricbot-" + UUID.randomUUID().toString().replace("-", "");
        String mount = request.workingDirectory() + ":/workspace:rw";
        StringBuilder command = new StringBuilder("docker run --rm --name ").append(shell(containerName)).append(' ');
        if (!networkEnabled) command.append("--network none ");
        command.append("-v ").append(shell(mount)).append(" -w /workspace ");
        request.environment().forEach((key, value) -> {
            if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                command.append("-e ").append(shell(key + "=" + value)).append(' ');
            }
        });
        command.append(shell(image)).append(" /bin/sh -lc ").append(shell(request.command()));
        try {
            ExecutionResult result = host.execute(new ExecutionRequest(
                    command.toString(), request.workingDirectory(), minimalHostEnvironment(),
                    request.timeout(), request.maxCaptureBytes()));
            boolean removed = !result.timedOut() || host.forceRemove(containerName);
            if (result.timedOut() && !removed) {
                throw new IllegalStateException("timed out Docker container could not be removed: " + containerName);
            }
            return new ExecutionResult(result.exitCode(), result.stdout(), result.stderr(), result.timedOut(),
                    result.truncated(), result.duration(), name(), Map.of(
                            "image", image,
                            "container_name", containerName,
                            "network_enabled", networkEnabled,
                            "isolation", "container",
                            "container_removed", removed
                    ));
        } catch (ExecutionInterruptedException interrupted) {
            boolean restoreInterrupt = Thread.interrupted();
            boolean removed;
            try { removed = host.forceRemove(containerName); }
            finally { if (restoreInterrupt) Thread.currentThread().interrupt(); }
            throw new ExecutionInterruptedException(name(),
                    interrupted.processTreeTerminated() && removed,
                    interrupted.remainingPids(), interrupted);
        } catch (Exception failure) {
            host.forceRemove(containerName);
            throw failure;
        }
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

    interface DockerHost {
        ExecutionCapabilities probe(boolean networkEnabled);
        ExecutionResult execute(ExecutionRequest request) throws Exception;
        boolean forceRemove(String containerName);
    }

    private static final class LocalDockerHost implements DockerHost {
        private final LocalExecutionBackend local = new LocalExecutionBackend();
        @Override public ExecutionCapabilities probe(boolean networkEnabled) {
            try {
                Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                        .redirectErrorStream(true).start();
                boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                String detail = finished ? new String(process.getInputStream().readAllBytes()).trim() : "probe timed out";
                if (!finished) process.destroyForcibly();
                return new ExecutionCapabilities(finished && process.exitValue() == 0,
                        true, !networkEnabled, false, detail);
            } catch (Exception failure) {
                return new ExecutionCapabilities(false, true, !networkEnabled, false,
                        failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName());
            }
        }
        @Override public ExecutionResult execute(ExecutionRequest request) throws Exception {
            return local.execute(request);
        }
        @Override public boolean forceRemove(String containerName) {
            try {
                Process cleanup = new ProcessBuilder("docker", "rm", "-f", containerName)
                        .redirectErrorStream(true).start();
                return cleanup.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                        && (cleanup.exitValue() == 0 || new String(cleanup.getInputStream().readAllBytes())
                        .contains("No such container"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception failure) {
                return false;
            }
        }
    }
}
