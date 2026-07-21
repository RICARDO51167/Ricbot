package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.worker.WorkerState;
import ricbot.domain.worker.WorkerStore;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.process.SpawnTool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnWorkerServiceTest {
    @Test
    void duplicateSpawnReturnsStableWorkerAndOneMailboxResult(@TempDir Path workspace) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SpawnWorkerService service = service(providerReturning("done", calls), workspace)) {
            SpawnWorkerService.SpawnReceipt first = service.spawn(
                    "整理 README", "整理任务", "cli", "direct", "cli:direct", "tool-call-1");
            SpawnWorkerService.SpawnReceipt duplicate = service.spawn(
                    "整理 README", "整理任务", "cli", "direct", "cli:direct", "tool-call-1");

            assertEquals(first.workerId(), duplicate.workerId());
            assertTrue(first.created());
            assertFalse(duplicate.created());
            assertTrue(waitUntil(() -> service.worker(first.workerId()).orElseThrow().state().status()
                    == WorkerState.Status.COMPLETED, 3000));
            assertEquals(1, calls.get());
            List<WorkerStore.MailboxMessage> results = service.inbox(first.controllerWorkerId(), true).stream()
                    .filter(message -> first.workerId().equals(message.payload().get("completion_key")))
                    .toList();
            assertEquals(1, results.size());
            assertEquals("done", results.get(0).payload().get("result"));
        }
    }

    @Test
    void restartRecoversSameWorkerWithoutCreatingAnother(@TempDir Path workspace) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        SpawnWorkerService firstService = service(providerThatWaits(started), workspace);
        SpawnWorkerService.SpawnReceipt first = firstService.spawn(
                "长任务", "恢复任务", "cli", "recover", "cli:recover", "stable-request");
        assertTrue(started.await(3, TimeUnit.SECONDS));
        firstService.close();

        AtomicInteger recoveredCalls = new AtomicInteger();
        try (SpawnWorkerService recovered = service(providerReturning("recovered", recoveredCalls), workspace)) {
            SpawnWorkerService.SpawnReceipt duplicate = recovered.spawn(
                    "长任务", "恢复任务", "cli", "recover", "cli:recover", "stable-request");

            assertEquals(first.workerId(), duplicate.workerId());
            assertFalse(duplicate.created());
            assertTrue(waitUntil(() -> recovered.worker(first.workerId()).orElseThrow().state().status()
                    == WorkerState.Status.COMPLETED, 3000));
            assertEquals(1, recovered.workers("cli:recover").stream()
                    .filter(worker -> "SPAWN".equals(worker.spec().role())).count());
            assertEquals(1, recoveredCalls.get());
            assertEquals(1, recovered.inbox(first.controllerWorkerId(), true).stream()
                    .filter(message -> first.workerId().equals(message.payload().get("completion_key"))).count());
        }
    }

    @Test
    void cancellationIsDurableAndReportedThroughMailbox(@TempDir Path workspace) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (SpawnWorkerService service = service(providerThatWaits(started), workspace)) {
            SpawnWorkerService.SpawnReceipt receipt = service.spawn(
                    "长任务", "取消任务", "cli", "cancel", "cli:cancel", "cancel-request");
            assertTrue(started.await(3, TimeUnit.SECONDS));

            assertEquals(1, service.cancelBySession("cli:cancel"));
            assertTrue(waitUntil(() -> service.worker(receipt.workerId()).orElseThrow().state().status()
                    == WorkerState.Status.CANCELLED, 3000));
            assertTrue(waitUntil(() -> service.inbox(receipt.controllerWorkerId(), true).stream()
                    .anyMatch(message -> "CANCELLED".equals(message.payload().get("status"))), 3000));
        }
    }

    @Test
    void spawnToolUsesProtocolIdempotencyKey(@TempDir Path workspace) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SpawnWorkerService service = service(providerReturning("done", calls), workspace)) {
            SpawnTool tool = new SpawnTool(service);
            tool.setContext("cli", "direct", "message-1");
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            Map<String, Object> args = Map.of(
                    "task", "inspect",
                    "label", "inspection",
                    "session_key", "cli:direct"
            );

            Object first = registry.executeProtocol("spawn", args, "invocation-1", "");
            Object duplicate = registry.executeProtocol("spawn", args, "invocation-1", "");
            assertNotNull(first);
            assertEquals(value(first, "worker_id"), value(duplicate, "worker_id"));
            assertEquals(Boolean.FALSE, value(duplicate, "created"));
        }
    }

    private static SpawnWorkerService service(LLMProvider provider, Path workspace) {
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        return new SpawnWorkerService(provider, workspace, 10_000, "gpt-test", exec, true);
    }

    private static LLMProvider providerReturning(String content, AtomicInteger calls) {
        return new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                    String model, Integer maxTokens, Double temperature,
                                    String reasoningEffort, Object toolChoice) {
                calls.incrementAndGet();
                return new LLMResponse().setContent(content).setFinishReason("stop");
            }
        };
    }

    private static LLMProvider providerThatWaits(CountDownLatch started) {
        return new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                    String model, Integer maxTokens, Double temperature,
                                    String reasoningEffort, Object toolChoice) {
                started.countDown();
                try {
                    while (true) Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("cancelled");
                }
            }
        };
    }

    private static Object value(Object result, String key) {
        return result instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static boolean waitUntil(Check check, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return true;
            Thread.sleep(25);
        }
        return check.ok();
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }
}
