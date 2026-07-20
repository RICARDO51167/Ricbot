package ricbot.infra.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenTelemetryRuntimeTest {
    @Test
    void createsAndClosesExplicitOtlpRuntimeWithoutRegisteringGlobalState() {
        OpenTelemetryRuntime runtime = OpenTelemetryRuntime.create("http://127.0.0.1:4317", "ricbot-test");
        try {
            assertTrue(runtime.exporting());
            assertEquals("http://127.0.0.1:4317", runtime.endpoint());
            assertNotNull(runtime.tracer("test", "1"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void rejectsNonHttpExporterEndpoint() {
        assertThrows(IllegalArgumentException.class, () ->
                OpenTelemetryRuntime.create("file:///tmp/traces", "ricbot"));
    }
}
