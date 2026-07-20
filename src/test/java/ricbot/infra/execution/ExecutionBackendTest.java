package ricbot.infra.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

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
    void remoteAdapterPreservesTransportNeutralContract(@TempDir Path workspace) throws Exception {
        RemoteExecutionBackend backend = new RemoteExecutionBackend("e2b", request ->
                new ExecutionResult(0, request.command(), "", false, false,
                        Duration.ofMillis(2), "client", Map.of("sandbox", "s-1")), null);

        ExecutionResult result = backend.execute(new ExecutionRequest(
                "echo remote", workspace, Map.of(), Duration.ofSeconds(2), 4096));

        assertEquals("remote:e2b", result.backend());
        assertEquals("s-1", result.metadata().get("sandbox"));
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
}
