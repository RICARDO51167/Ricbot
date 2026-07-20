package ricbot.infra.execution;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Bounded local process backend with concurrent pipe draining and process-tree termination. */
public final class LocalExecutionBackend implements ExecutionBackend {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public String name() { return "local"; }

    public ExecutionCapabilities probe() {
        return new ExecutionCapabilities(true, false, false, false, "host process execution");
    }

    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        Instant started = Instant.now();
        List<String> command = WINDOWS
                ? List.of("cmd.exe", "/c", request.command())
                : List.of("/bin/sh", "-lc", request.command());
        ProcessBuilder builder = new ProcessBuilder(command).directory(request.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(request.environment());
        Process process = builder.start();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<Capture> stdout = readers.submit(() -> capture(process.getInputStream(), request.maxCaptureBytes()));
        Future<Capture> stderr = readers.submit(() -> capture(process.getErrorStream(), request.maxCaptureBytes()));
        boolean finished = false;
        try {
            finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Capture out = stdout.get(5, TimeUnit.SECONDS);
            Capture err = stderr.get(5, TimeUnit.SECONDS);
            return new ExecutionResult(
                    finished ? process.exitValue() : -1,
                    out.text(), err.text(), !finished, out.truncated() || err.truncated(),
                    Duration.between(started, Instant.now()), name(), Map.of("pid", process.pid()));
        } finally {
            stdout.cancel(!finished);
            stderr.cancel(!finished);
            readers.shutdownNow();
        }
    }

    private static Capture capture(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[4096];
        int read;
        boolean truncated = false;
        while ((read = stream.read(buffer)) >= 0) {
            int remaining = limit - bytes.size();
            if (remaining > 0) bytes.write(buffer, 0, Math.min(remaining, read));
            if (read > remaining) truncated = true;
        }
        return new Capture(bytes.toString(StandardCharsets.UTF_8), truncated);
    }

    private static void terminateTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private record Capture(String text, boolean truncated) { }
}
