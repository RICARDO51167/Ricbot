package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.context.StructuredContextService;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.SessionManager;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionPreparationServiceTest {
    @Test
    void clearsLegacyPendingMarkerWithoutSynthesizingRecoveryMessages(@TempDir Path workspace) {
        SessionManager sessions = new SessionManager(workspace);
        var session = sessions.getOrCreate("session");
        session.addMessage("user", "waiting");
        session.getMetadata().put(SessionRuntimeKeys.PENDING_USER_TURN_KEY, true);
        sessions.save(session);
        SessionPreparationService service = new SessionPreparationService(sessions, compactor(sessions));
        PreparedSessionContext prepared = service.prepareSystemTurn("session");
        assertEquals(1, prepared.session().getMessages().size());
        assertEquals("user", prepared.session().getMessages().get(0).get("role"));
        assertFalse(prepared.session().getMetadata().containsKey(SessionRuntimeKeys.PENDING_USER_TURN_KEY));
    }

    @Test
    void dispatchesCommandsWithoutLegacyCheckpointRecovery(@TempDir Path workspace) {
        SessionManager sessions = new SessionManager(workspace);
        SessionPreparationService service = new SessionPreparationService(sessions, compactor(sessions));
        PreparedSessionContext prepared = service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "chat", "/status"), "session",
                (message, session, key, raw) -> new ricbot.domain.message.OutboundMessage("cli", "chat", "ok"));
        assertEquals("ok", prepared.immediateResponse().getContent());
    }

    private static StructuredContextService compactor(SessionManager sessions) {
        LLMProvider provider = new LLMProvider("k", "local") {
            @Override public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                    String model, Integer maxTokens, Double temperature, String reasoningEffort, Object toolChoice) {
                return new LLMResponse().setContent("{}").setFinishReason("stop");
            }
        };
        return new StructuredContextService(provider, "test", sessions, 64_000, 4_096);
    }
}
