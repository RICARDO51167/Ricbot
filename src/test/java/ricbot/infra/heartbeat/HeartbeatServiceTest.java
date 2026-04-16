package ricbot.infra.heartbeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatServiceTest {

    @Test
    void startStop_canRestart(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("HEARTBEAT.md"), "no tasks");

        HeartbeatService service = new HeartbeatService(
                workspace,
                noOpProvider(),
                "gpt-4o-mini",
                tasks -> "ok",
                msg -> {
                },
                1,
                true,
                "UTC"
        );

        service.start();
        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());

        service.start();
        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    void tickFailure_setsLastError(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("HEARTBEAT.md"), "active");
        AtomicInteger notifyCalls = new AtomicInteger(0);

        HeartbeatService service = new HeartbeatService(
                workspace,
                providerReturningRunDecision(),
                "gpt-4o-mini",
                tasks -> {
                    throw new RuntimeException("boom");
                },
                msg -> notifyCalls.incrementAndGet(),
                1,
                true,
                "UTC"
        );

        service.start();
        Thread.sleep(1500);
        service.stop();

        assertNotNull(service.getLastErrorMessage());
        assertTrue(service.getLastErrorMessage().contains("boom"), service.getLastErrorMessage());
        assertEquals(0, notifyCalls.get());
    }

    private static LLMProvider noOpProvider() {
        return new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                return new LLMResponse().setContent("skip").setFinishReason("stop");
            }
        };
    }

    private static LLMProvider providerReturningRunDecision() {
        return new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                return new LLMResponse()
                        .setToolCalls(List.of(new ToolCallRequest(
                                "hb_1",
                                "heartbeat",
                                Map.of("action", "run", "tasks", "execute")
                        )))
                        .setFinishReason("tool_calls");
            }
        };
    }
}
