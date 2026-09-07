package ricbot.infra.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.config.Config;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionBackendTest {
    @Test
    void localBackendCapturesExitOutputAndMetadata(@TempDir Path workspace) throws Exception {
        ExecutionResult result = new LocalExecutionBackend().execute(new ExecutionRequest(
                "printf hello", workspace, Map.of("PATH", System.getenv().getOrDefault("PATH", "")),
                Duration.ofSeconds(5), 4096));

        assertEquals(0, result.exitCode());
        assertEquals("hello", result.stdout());
        assertEquals("local", result.backend());
        assertFalse(result.timedOut());
    }

    @Test
    void fallbackMustBeExplicit() {
        ExecutionBackend unavailable = new ExecutionBackend() {
            public String name() { return "docker"; }
            public ExecutionCapabilities probe() {
                return new ExecutionCapabilities(false, true, true, false, "daemon down");
            }
            public ExecutionResult execute(ExecutionRequest request) { throw new AssertionError(); }
        };
        ExecutionBackendRegistry registry = new ExecutionBackendRegistry()
                .register(unavailable).register(new LocalExecutionBackend());

        assertThrows(IllegalStateException.class, () -> registry.select("docker", "local", false));
        ExecutionBackendRegistry.Selection selection = registry.select("docker", "local", true);
        assertTrue(selection.fallbackUsed());
        assertEquals("local", selection.backend().name());
        assertTrue(selection.reason().contains("daemon down"));
    }

    @Test
    void rejectsUnsafeDockerImageReferences() {
        assertThrows(IllegalArgumentException.class, () -> new DockerExecutionBackend("image; touch /tmp/x"));
    }

    @Test
    void factorySelectsConfiguredBackendAndAuditsSelection(@TempDir Path workspace) throws Exception {
        Config.ExecToolConfig config = new Config.ExecToolConfig();
        config.setBackend("local");

        ExecutionBackend backend = ExecutionBackendFactory.create(config);
        ExecutionResult result = backend.execute(new ExecutionRequest(
                "printf selected", workspace, Map.of("PATH", System.getenv().getOrDefault("PATH", "")),
                Duration.ofSeconds(5), 4096));

        assertEquals("local", backend.name());
        assertEquals("local", result.metadata().get("preferred_backend"));
        assertEquals(false, result.metadata().get("fallback_used"));
    }

    @Test
    void factoryRejectsUnknownBackendWithoutSilentLocalFallback() {
        Config.ExecToolConfig config = new Config.ExecToolConfig();
        config.setBackend("missing");
        config.setAllowBackendFallback(false);

        assertThrows(IllegalArgumentException.class, () -> ExecutionBackendFactory.create(config));
    }

    @Test
    void interruptTerminatesProcessTreeBeforeReturning(@TempDir Path workspace) throws Exception {
        Path marker = workspace.resolve("marker.txt");
        String quoted = "'" + marker.toString().replace("'", "'\"'\"'") + "'";
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                new LocalExecutionBackend().execute(new ExecutionRequest(
                        "(sleep 2; printf late > " + quoted + ") & wait", workspace,
                        Map.of("PATH", System.getenv().getOrDefault("PATH", "")),
                        Duration.ofSeconds(20), 4096));
            } catch (Throwable failure) {
                outcome.set(failure);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        Thread.sleep(250);
        worker.interrupt();
        worker.join(7_000);

        assertFalse(worker.isAlive());
        ExecutionInterruptedException interrupted = assertInstanceOf(
                ExecutionInterruptedException.class, outcome.get());
        assertTrue(interrupted.processTreeTerminated(), interrupted.getMessage());
        assertTrue(interrupted.remainingPids().isEmpty());
        assertTrue(interruptRestored.get());
        Thread.sleep(2_200);
        assertFalse(Files.exists(marker), "a descendant wrote after cancellation completed");
    }

    @Test
    void timeoutTerminatesProcessTreeBeforeReturning(@TempDir Path workspace) throws Exception {
        Path marker = workspace.resolve("timeout-marker.txt");
        String quoted = "'" + marker.toString().replace("'", "'\"'\"'") + "'";
        ExecutionResult result = new LocalExecutionBackend().execute(new ExecutionRequest(
                "(sleep 1; printf late > " + quoted + ") & wait", workspace,
                Map.of("PATH", System.getenv().getOrDefault("PATH", "")),
                Duration.ofMillis(100), 4096));

        assertTrue(result.timedOut());
        assertEquals(true, result.metadata().get("process_tree_terminated"));
        assertEquals(List.of(), result.metadata().get("remaining_pids"));
        Thread.sleep(1_200);
        assertFalse(Files.exists(marker), "a descendant wrote after timeout cleanup completed");
    }

    @Test
    void dockerForcesContainerRemovalAfterInterruption(@TempDir Path workspace) {
        AtomicBoolean removed = new AtomicBoolean();
        DockerExecutionBackend.DockerHost host = new DockerExecutionBackend.DockerHost() {
            public ExecutionCapabilities probe(boolean networkEnabled) {
                return new ExecutionCapabilities(true, true, !networkEnabled, false, "test");
            }
            public ExecutionResult execute(ExecutionRequest request) {
                assertTrue(request.command().contains("--name"));
                throw new ExecutionInterruptedException("local", true, List.of(), null);
            }
            public boolean forceRemove(String containerName) {
                removed.set(true);
                return containerName.startsWith("ricbot-");
            }
        };
        DockerExecutionBackend backend = new DockerExecutionBackend("eclipse-temurin:17-jdk", false, host);
        ExecutionInterruptedException failure = assertThrows(ExecutionInterruptedException.class,
                () -> backend.execute(new ExecutionRequest("sleep 10", workspace, Map.of(),
                        Duration.ofSeconds(20), 4096)));
        assertEquals("docker", failure.backend());
        assertTrue(failure.processTreeTerminated());
        assertTrue(removed.get());
    }

    @Test
    void dockerForcesContainerRemovalAfterTimeout(@TempDir Path workspace) throws Exception {
        AtomicBoolean removed = new AtomicBoolean();
        DockerExecutionBackend.DockerHost host = new DockerExecutionBackend.DockerHost() {
            public ExecutionCapabilities probe(boolean networkEnabled) {
                return new ExecutionCapabilities(true, true, !networkEnabled, false, "test");
            }
            public ExecutionResult execute(ExecutionRequest request) {
                return new ExecutionResult(-1, "", "", true, false, Duration.ofSeconds(1),
                        "local", Map.of("process_tree_terminated", true));
            }
            public boolean forceRemove(String containerName) { removed.set(true); return true; }
        };
        ExecutionResult result = new DockerExecutionBackend("eclipse-temurin:17-jdk", false, host)
                .execute(new ExecutionRequest("sleep 10", workspace, Map.of(), Duration.ofSeconds(1), 4096));
        assertTrue(result.timedOut());
        assertEquals(true, result.metadata().get("container_removed"));
        assertTrue(removed.get());
    }
}
