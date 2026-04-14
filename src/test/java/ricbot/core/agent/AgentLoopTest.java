package ricbot.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.core.message.InboundMessage;
import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;
import ricbot.core.session.SessionManager;
import ricbot.infra.config.Config;
import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.LLMResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AgentLoopTest {

    @Test
    void endToEnd_cliToSessionPersistence(@TempDir Path workspace) throws Exception {
        MessageBus bus = new MessageBus();
        SessionManager sessionManager = new SessionManager(workspace);

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
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
                return new LLMResponse().setContent("pong").setFinishReason("stop");
            }
        };

        Config.WebToolsConfig web = new Config.WebToolsConfig();
        web.setEnable(false);
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);

        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                workspace,
                "gpt-4o-mini",
                5,
                2000,
                50,
                10_000,
                "standard",
                web,
                exec,
                true,
                sessionManager,
                "UTC",
                false,
                List.of(),
                0
        );

        Thread t = new Thread(loop::run);
        t.start();
        try {
            InboundMessage inbound = new InboundMessage("cli", "user", "direct", "ping");
            bus.publishInbound(inbound);

            OutboundMessage out = bus.pollOutbound(3000);
            assertNotNull(out);
            assertEquals("pong", out.getContent());

            Path sessionsDir = workspace.resolve("sessions");
            assertTrue(Files.exists(sessionsDir));
            assertTrue(Files.list(sessionsDir).anyMatch(p -> p.getFileName().toString().contains("cli_direct")));
        } finally {
            loop.stop();
            t.interrupt();
            t.join(2000);
        }
    }
}

