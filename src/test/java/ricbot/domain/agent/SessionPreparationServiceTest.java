package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.memory.Consolidator;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionPreparationServiceTest {

    @Test
    void prepareInteractiveTurn_restoresCheckpointAndPendingToolFailure(@TempDir Path workspace) {
        Fixture fixture = fixture(workspace);
        Session session = fixture.sessions.getOrCreate("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, Map.of(
                "goal", "old goal",
                "status", "active",
                "current_step", "waiting"
        ));
        session.getMetadata().put(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY, new LinkedHashMap<>(Map.of(
                "assistant_message", new LinkedHashMap<>(Map.of("role", "assistant", "content", "working")),
                "completed_tool_results", List.of(new LinkedHashMap<>(Map.of(
                        "role", "tool",
                        "tool_call_id", "call_done",
                        "name", "list_dir",
                        "content", "done"
                ))),
                "pending_tool_calls", List.of(new LinkedHashMap<>(Map.of(
                        "id", "call_pending",
                        "function", new LinkedHashMap<>(Map.of("name", "read_file"))
                ))),
                "interruption_reason", "manual_stop",
                "task_state", new LinkedHashMap<>(Map.of(
                        "goal", "restore goal",
                        "status", "blocked",
                        "current_step", "tool failed",
                        "blocked_reason", "manual stop"
                ))
        )));
        session.getMetadata().put("_last_interrupt_reason", "manual_stop");
        fixture.sessions.save(session);

        PreparedSessionContext prepared = fixture.service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "direct", "hello"),
                "cli:direct",
                (msg, currentSession, key, raw) -> null
        );

        assertNull(prepared.immediateResponse());
        List<Map<String, Object>> messages = prepared.session().getMessages();
        assertEquals(3, messages.size());
        assertEquals("assistant", messages.get(0).get("role"));
        assertEquals("tool", messages.get(1).get("role"));
        assertEquals("tool", messages.get(2).get("role"));
        assertTrue(String.valueOf(messages.get(2).get("content")).contains("手动停止"));
        assertEquals("restore goal", String.valueOf(prepared.session().getMetadata()
                .get(SessionRuntimeKeys.TASK_STATE_KEY) instanceof Map<?, ?> map ? map.get("goal") : ""));
        assertFalse(prepared.session().getMetadata().containsKey(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY));
        assertFalse(prepared.session().getMetadata().containsKey(SessionRuntimeKeys.PENDING_USER_TURN_KEY));
    }

    @Test
    void prepareSystemTurn_restoresInterruptedUserTurn(@TempDir Path workspace) {
        Fixture fixture = fixture(workspace);
        Session session = fixture.sessions.getOrCreate("cli:direct");
        session.addMessage("user", "still waiting");
        session.getMetadata().put(SessionRuntimeKeys.PENDING_USER_TURN_KEY, true);
        fixture.sessions.save(session);

        PreparedSessionContext prepared = fixture.service.prepareSystemTurn("cli:direct");

        List<Map<String, Object>> messages = prepared.session().getMessages();
        assertEquals(2, messages.size());
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("错误：任务在生成回复前被中断。", messages.get(1).get("content"));
        assertFalse(prepared.session().getMetadata().containsKey(SessionRuntimeKeys.PENDING_USER_TURN_KEY));
    }

    @Test
    void prepareInteractiveTurn_shortCircuitsSlashCommands(@TempDir Path workspace) {
        Fixture fixture = fixture(workspace);
        OutboundMessage commandResponse = new OutboundMessage();
        commandResponse.setContent("handled");

        PreparedSessionContext prepared = fixture.service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "direct", "/help"),
                "cli:direct",
                (msg, currentSession, key, raw) -> commandResponse
        );

        assertSame(commandResponse, prepared.immediateResponse());
    }

    private Fixture fixture(Path workspace) {
        SessionManager sessions = new SessionManager(workspace);
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
                return new LLMResponse().setContent("ok").setFinishReason("stop");
            }
        };
        Consolidator consolidator = new Consolidator(
                new MemoryStore(workspace),
                provider,
                "test-model",
                sessions,
                4000,
                1024
        );
        AutoCompact autoCompact = new AutoCompact(sessions, consolidator, 0);
        return new Fixture(sessions, new SessionPreparationService(sessions, autoCompact, consolidator));
    }

    private record Fixture(SessionManager sessions, SessionPreparationService service) {
    }
}
