package ricbot.domain.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SubagentManagerTest {

    @Test
    void spawn_completesAndPublishesSystemAnnouncement(@TempDir Path workspace) throws Exception {
        MessageBus bus = new MessageBus();
        SubagentManager manager = new SubagentManager(
                providerReturning("done"),
                workspace,
                bus,
                10_000,
                "gpt-4o-mini",
                disabledWeb(),
                disabledExec(),
                true,
                List.of()
        );

        try {
            String startMessage = manager.spawn("整理 README", "整理任务", "cli", "direct", "cli:direct");
            assertTrue(startMessage.contains("子代理 [整理任务] 已启动"));

            InboundMessage inbound = bus.consumeInbound(3, TimeUnit.SECONDS);
            assertNotNull(inbound);
            assertEquals("system", inbound.getChannel());
            assertEquals("subagent", inbound.getSenderId());
            assertEquals("cli:direct", inbound.getChatId());
            assertTrue(inbound.getContent().contains("整理任务"));
            assertTrue(inbound.getContent().contains("已完成"));
            assertTrue(inbound.getContent().contains("done"));
        } finally {
            manager.close();
        }
    }

    @Test
    void cancelBySession_cancelsRunningSubagentAndPublishesCancellation(@TempDir Path workspace) throws Exception {
        MessageBus bus = new MessageBus();
        CountDownLatch started = new CountDownLatch(1);
        SubagentManager manager = new SubagentManager(
                providerThatWaitsForCancellation(started),
                workspace,
                bus,
                10_000,
                "gpt-4o-mini",
                disabledWeb(),
                disabledExec(),
                true,
                List.of()
        );

        try {
            manager.spawn("长任务", "阻塞任务", "cli", "direct", "cli:cancel");
            assertTrue(started.await(3, TimeUnit.SECONDS));

            int cancelled = manager.cancelBySession("cli:cancel");
            assertEquals(1, cancelled);
            assertTrue(waitUntil(() -> manager.getRunningCount() == 0, 3000));
        } finally {
            manager.close();
        }
    }

    private static LLMProvider providerReturning(String content) {
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
                return new LLMResponse().setContent(content).setFinishReason("stop");
            }
        };
    }

    private static LLMProvider providerThatWaitsForCancellation(CountDownLatch started) {
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
                started.countDown();
                try {
                    while (true) {
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("cancelled");
                }
            }
        };
    }

    private static Config.WebToolsConfig disabledWeb() {
        Config.WebToolsConfig web = new Config.WebToolsConfig();
        web.setEnable(false);
        return web;
    }

    private static Config.ExecToolConfig disabledExec() {
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        return exec;
    }

    private static boolean waitUntil(Check check, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return true;
            }
            Thread.sleep(50);
        }
        return check.ok();
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }
}
