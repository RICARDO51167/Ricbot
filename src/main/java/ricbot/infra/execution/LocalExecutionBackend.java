package ricbot.infra.execution;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Bounded local process backend with concurrent pipe draining and process-tree termination. */
public final class LocalExecutionBackend implements ExecutionBackend {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String POSIX_WRAPPER = """
            set -m
            ricbot_cleanup() {
              trap - TERM INT HUP
              if [ -n "${ricbot_child:-}" ]; then
                kill -TERM -- "-$ricbot_child" 2>/dev/null || true
                ricbot_attempt=0
                while kill -0 -- "-$ricbot_child" 2>/dev/null && [ "$ricbot_attempt" -lt 5 ]; do
                  sleep 0.1
                  ricbot_attempt=$((ricbot_attempt + 1))
                done
                kill -KILL -- "-$ricbot_child" 2>/dev/null || true
                wait "$ricbot_child" 2>/dev/null || true
              fi
              exit 143
            }
            trap ricbot_cleanup TERM INT HUP
            /bin/sh -lc "$1" &
            ricbot_child=$!
            wait "$ricbot_child"
            ricbot_status=$?
            trap - TERM INT HUP
            exit "$ricbot_status"
            """;

    public String name() { return "local"; }

    public ExecutionCapabilities probe() {
        return new ExecutionCapabilities(true, false, false, false, "host process execution");
    }

    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        Instant started = Instant.now();
        List<String> command = WINDOWS
                ? List.of("cmd.exe", "/c", request.command())
                : List.of("/bin/sh", "-c", POSIX_WRAPPER, "ricbot-exec", request.command());
        ProcessBuilder builder = new ProcessBuilder(command).directory(request.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(request.environment());
        Process process = builder.start();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<Capture> stdout = readers.submit(() -> capture(process.getInputStream(), request.maxCaptureBytes()));
        Future<Capture> stderr = readers.submit(() -> capture(process.getErrorStream(), request.maxCaptureBytes()));
        boolean finished = false;
        Termination timeoutTermination = null;
        try {
            finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timeoutTermination = safeTerminateTree(process, Duration.ofSeconds(5));
                if (!timeoutTermination.complete()) {
                    throw new IllegalStateException("process tree did not terminate after timeout; remainingPids="
                            + timeoutTermination.remainingPids());
                }
            }
            Capture out = stdout.get(5, TimeUnit.SECONDS);
            Capture err = stderr.get(5, TimeUnit.SECONDS);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("pid", process.pid());
            if (timeoutTermination != null) {
                metadata.put("process_tree_terminated", timeoutTermination.complete());
                metadata.put("remaining_pids", timeoutTermination.remainingPids());
            }
            return new ExecutionResult(
                    finished ? process.exitValue() : -1,
                    out.text(), err.text(), !finished, out.truncated() || err.truncated(),
                    Duration.between(started, Instant.now()), name(), Map.copyOf(metadata));
        } catch (InterruptedException interrupted) {
            Termination termination = safeTerminateTree(process, Duration.ofSeconds(5));
            Thread.currentThread().interrupt();
            throw new ExecutionInterruptedException(name(), termination.complete(),
                    termination.remainingPids(), interrupted);
        } catch (Exception failure) {
            Termination termination = safeTerminateTree(process, Duration.ofSeconds(5));
            if (!termination.complete()) {
                failure.addSuppressed(new IllegalStateException(
                        "process cleanup incomplete; remainingPids=" + termination.remainingPids()));
            }
            throw failure;
        } finally {
            stdout.cancel(true);
            stderr.cancel(true);
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

    private static Termination terminateTree(Process process, Duration timeout) {
        long started = System.nanoTime();
        long timeoutNanos = Math.max(1L, timeout.toNanos());
        long deadline = started + timeoutNanos;
        long gracefulDrainNanos = Math.min(TimeUnit.SECONDS.toNanos(1),
                Math.max(1L, timeoutNanos / 3));
        Map<Long, ProcessHandle> observed = new LinkedHashMap<>();

        /*
         * The POSIX launcher owns a separate process group and handles TERM by stopping that group.
         * ProcessHandle snapshots remain a fallback for platforms where descendants are visible.
         */
        observeDescendants(process.toHandle(), observed);
        if (process.isAlive()) process.destroy();
        while (System.nanoTime() - started < gracefulDrainNanos) {
            observeDescendants(process.toHandle(), observed);
            if (observed.values().stream().noneMatch(ProcessHandle::isAlive) && !process.isAlive()) {
                return new Termination(true, List.of());
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }

        for (ProcessHandle handle : observed.values()) {
            if (!handle.isAlive()) continue;
            handle.destroyForcibly();
            awaitExit(handle, TimeUnit.MILLISECONDS.toNanos(100), deadline);
        }
        if (process.isAlive()) process.destroyForcibly();
        while (deadline - System.nanoTime() > 0L) {
            observeDescendants(process.toHandle(), observed);
            observed.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            if (!process.isAlive() && observed.values().stream().noneMatch(ProcessHandle::isAlive)) {
                return new Termination(true, List.of());
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }

        observeDescendants(process.toHandle(), observed);
        observed.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        List<Long> remaining = new ArrayList<>();
        if (process.isAlive()) remaining.add(process.pid());
        observed.values().stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).forEach(remaining::add);
        return new Termination(remaining.isEmpty(), List.copyOf(remaining));
    }

    private static void observeDescendants(ProcessHandle parent, Map<Long, ProcessHandle> observed) {
        List<ProcessHandle> children;
        try {
            children = parent.children().toList();
        } catch (RuntimeException processExitedDuringSnapshot) {
            return;
        }
        for (ProcessHandle child : children) observed.putIfAbsent(child.pid(), child);
        for (ProcessHandle child : children) observeDescendants(child, observed);
    }

    private static void awaitExit(ProcessHandle handle, long waitNanos, long globalDeadline) {
        long started = System.nanoTime();
        while (handle.isAlive()
                && System.nanoTime() - started < waitNanos
                && globalDeadline - System.nanoTime() > 0L) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    private static Termination safeTerminateTree(Process process, Duration timeout) {
        try {
            return terminateTree(process, timeout);
        } catch (RuntimeException cleanupFailure) {
            try { if (process.isAlive()) process.destroyForcibly(); }
            catch (RuntimeException ignored) { cleanupFailure.addSuppressed(ignored); }
            boolean alive = process.isAlive();
            return new Termination(!alive, alive ? List.of(process.pid()) : List.of());
        }
    }

    private record Capture(String text, boolean truncated) { }
    private record Termination(boolean complete, List<Long> remainingPids) { }
}
