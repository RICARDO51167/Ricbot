package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionPersistenceServiceTest {

    @Test
    void persistInteractiveTurn_truncatesToolResultsSkipsBlankAssistantAndClearsRuntimeFlags(@TempDir Path workspace) {
        SessionManager sessions = new SessionManager(workspace);
        SessionPersistenceService service = new SessionPersistenceService(sessions, 12);
        Session session = new Session("cli:direct");
        session.addMessage("assistant", "old reply");
        session.getMetadata().put(SessionRuntimeKeys.PENDING_USER_TURN_KEY, true);
        session.getMetadata().put(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY, Map.of("id", "cp"));
        session.getMetadata().put("_last_interrupt_reason", "manual_stop");

        AgentRequestContext request = new AgentRequestContext(
                new InboundMessage("cli", "user", "direct", "hello"),
                "cli:direct",
                session,
                "",
                new PromptContextBundle(),
                List.of(Map.of("role", "assistant", "content", "old reply")),
                List.of(),
                null,
                true
        );
        ExecutionOutcome outcome = new ExecutionOutcome(
                new AgentRunResult()
                        .setMessages(List.of(
                                Map.of("role", "system", "content", "ignored"),
                                Map.of("role", "assistant", "content", "old reply"),
                                Map.of("role", "user", "content", "hello"),
                                new LinkedHashMap<>(Map.of(
                                        "role", "tool",
                                        "tool_call_id", "call_1",
                                        "name", "read_file",
                                        "content", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                                )),
                                Map.of("role", "assistant", "content", ""),
                                Map.of("role", "assistant", "content", "done")
                        ))
                        .setToolEvents(List.of(new LinkedHashMap<>(Map.of(
                                "name", "read_file",
                                "status", "ok",
                                "detail", "done",
                                "result_summary", "file content"
                        ))))
                        .setFinalContent("done"),
                "done"
        );

        PersistenceResult persistence = service.persistInteractiveTurn(request, outcome);

        assertEquals("done", persistence.outboundMessage().getContent());
        assertEquals(3, session.getMessages().size());
        assertEquals("tool", session.getMessages().get(1).get("role"));
        String toolContent = String.valueOf(session.getMessages().get(1).get("content"));
        assertTrue(toolContent.startsWith("0123456789AB"));
        assertTrue(toolContent.contains("(truncated)"));
        assertEquals("done", session.getMessages().get(2).get("content"));
        assertTrue(session.getMetadata().containsKey(SessionRuntimeKeys.TOOL_TRACE_KEY));
        assertEquals("completed", String.valueOf(((Map<?, ?>) session.getMetadata().get(SessionRuntimeKeys.TASK_STATE_KEY)).get("status")));
        assertFalse(session.getMetadata().containsKey(SessionRuntimeKeys.PENDING_USER_TURN_KEY));
        assertFalse(session.getMetadata().containsKey(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY));
        assertFalse(session.getMetadata().containsKey("_last_interrupt_reason"));
    }

    @Test
    void persistInteractiveTurn_stripsRuntimeContextWrapperFromSavedUserMessage(@TempDir Path workspace) {
        SessionManager sessions = new SessionManager(workspace);
        SessionPersistenceService service = new SessionPersistenceService(sessions, 100);
        Session session = new Session("cli:direct");

        AgentRequestContext request = new AgentRequestContext(
                new InboundMessage("cli", "user", "direct", "hello"),
                "cli:direct",
                session,
                "",
                new PromptContextBundle(),
                List.of(),
                List.of(),
                null,
                false
        );
        String runtimeWrapped = ContextBuilder.buildRuntimeContext("cli", "direct", "UTC") + "\nhello";
        ExecutionOutcome outcome = new ExecutionOutcome(
                new AgentRunResult()
                        .setMessages(List.of(
                                Map.of("role", "system", "content", "ignored"),
                                Map.of("role", "user", "content", runtimeWrapped)
                        ))
                        .setFinalContent("ok"),
                "ok"
        );

        service.persistInteractiveTurn(request, outcome);

        assertEquals(1, session.getMessages().size());
        assertEquals("hello", session.getMessages().get(0).get("content"));
    }
}
