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
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    void prepareInteractiveTurn_restoresStandaloneDurableCheckpoint(@TempDir Path workspace) {
        FileRunCheckpointStore checkpointStore = new FileRunCheckpointStore(workspace);
        RunCheckpoint checkpoint = RunCheckpoint.fromPayload("cli:direct", Map.of(
                "checkpoint_id", "run-42:3:TOOLS_COMPLETED",
                "run_id", "run-42",
                "iteration", 3,
                "phase", "TOOLS_COMPLETED",
                "run_messages", List.of(
                        Map.of("role", "assistant", "content", "first step"),
                        Map.of(
                                "role", "tool",
                                "tool_call_id", "call-0",
                                "name", "list_dir",
                                "content", "listed"
                        ),
                        Map.of("role", "assistant", "content", "working")
                ),
                "assistant_message", Map.of("role", "assistant", "content", "working"),
                "completed_tool_results", List.of(Map.of(
                        "role", "tool",
                        "tool_call_id", "call-1",
                        "name", "read_file",
                        "content", "done"
                )),
                "pending_tool_calls", List.of()
        ), Map.of("goal", "durable recovery", "status", "active"));
        checkpointStore.save(checkpoint);
        Fixture fixture = fixture(workspace, checkpointStore);

        PreparedSessionContext prepared = fixture.service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "direct", "continue"),
                "cli:direct",
                (msg, currentSession, key, raw) -> null
        );

        assertEquals(3, prepared.session().getMessages().size());
        assertEquals("first step", prepared.session().getMessages().get(0).get("content"));
        assertEquals("listed", prepared.session().getMessages().get(1).get("content"));
        assertEquals("working", prepared.session().getMessages().get(2).get("content"));
        assertEquals("run-42:3:TOOLS_COMPLETED", prepared.session().getMetadata()
                .get(SessionRuntimeKeys.LAST_RESTORED_CHECKPOINT_ID_KEY));
        assertTrue(checkpointStore.load("cli:direct").isEmpty());
    }

    @Test
    void prepareInteractiveTurn_doesNotReplayStaleCommittedCheckpoint(@TempDir Path workspace) {
        FileRunCheckpointStore checkpointStore = new FileRunCheckpointStore(workspace);
        RunCheckpoint checkpoint = RunCheckpoint.fromPayload("cli:direct", Map.of(
                "checkpoint_id", "run-42:3:TOOLS_COMPLETED",
                "run_id", "run-42",
                "iteration", 3,
                "phase", "TOOLS_COMPLETED",
                "assistant_message", Map.of("role", "assistant", "content", "must not duplicate")
        ), Map.of());
        checkpointStore.save(checkpoint);
        Fixture fixture = fixture(workspace, checkpointStore);
        Session session = fixture.sessions.getOrCreate("cli:direct");
        session.addMessage("assistant", "already committed");
        session.getMetadata().put(
                SessionRuntimeKeys.LAST_RESTORED_CHECKPOINT_ID_KEY,
                checkpoint.checkpointId()
        );
        fixture.sessions.save(session);

        PreparedSessionContext prepared = fixture.service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "direct", "continue"),
                "cli:direct",
                (msg, currentSession, key, raw) -> null
        );

        assertEquals(1, prepared.session().getMessages().size());
        assertEquals("already committed", prepared.session().getMessages().get(0).get("content"));
        assertTrue(checkpointStore.load("cli:direct").isEmpty());
    }

    @Test
    void prepareInteractiveTurn_prefersNewerStandaloneCheckpoint(@TempDir Path workspace) {
        FileRunCheckpointStore checkpointStore = new FileRunCheckpointStore(workspace);
        checkpointStore.save(new RunCheckpoint(
                RunCheckpoint.CURRENT_SCHEMA_VERSION,
                "run-new:2:MODEL_RESPONSE_RECEIVED",
                "run-new",
                "run-new",
                "cli:direct",
                2,
                0,
                2,
                RunCheckpointPhase.MODEL_RESPONSE_RECEIVED,
                AgentNodeState.initial(),
                List.of(Map.of("role", "assistant", "content", "new durable state")),
                Map.of("role", "assistant", "content", "new durable state"),
                List.of(),
                List.of(),
                Map.of(),
                "",
                Instant.parse("2026-07-20T00:00:02Z")
        ));
        Fixture fixture = fixture(workspace, checkpointStore);
        Session session = fixture.sessions.getOrCreate("cli:direct");
        session.getMetadata().put(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY, Map.of(
                "checkpoint_id", "run-old:1:MODEL_RESPONSE_RECEIVED",
                "assistant_message", Map.of("role", "assistant", "content", "old session state"),
                "updated_at", "2026-07-20T00:00:01Z"
        ));
        fixture.sessions.save(session);

        PreparedSessionContext prepared = fixture.service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "direct", "continue"),
                "cli:direct",
                (msg, currentSession, key, raw) -> null
        );

        assertEquals(1, prepared.session().getMessages().size());
        assertEquals("new durable state", prepared.session().getMessages().get(0).get("content"));
        assertEquals("run-new:2:MODEL_RESPONSE_RECEIVED", prepared.session().getMetadata()
                .get(SessionRuntimeKeys.LAST_RESTORED_CHECKPOINT_ID_KEY));
    }

    @Test
    void prepareInteractiveTurn_marksInterruptedToolOutcomeUnknown(@TempDir Path workspace) {
        FileRunJournalStore journalStore = new FileRunJournalStore(workspace);
        String runId = "run-interrupted";
        ToolInvocationRecord running = ToolInvocationRecord.running(
                runId,
                1,
                "call-1",
                "write_file",
                Map.of("path", "result.txt"),
                false,
                "side_effect"
        );
        journalStore.append(RunEvent.create(
                1, runId, "cli:direct", 0,
                RunEventType.RUN_STARTED, RunStatus.CREATED, null, Map.of()
        ));
        journalStore.append(RunEvent.create(
                2, runId, "cli:direct", 1,
                RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null, Map.of()
        ));
        journalStore.append(RunEvent.create(
                3, runId, "cli:direct", 1,
                RunEventType.MODEL_RESPONSE_RECEIVED, RunStatus.WAITING_TOOL, null, Map.of()
        ));
        journalStore.append(RunEvent.create(
                4, runId, "cli:direct", 1,
                RunEventType.TOOL_CALL_STARTED, RunStatus.TOOL_RUNNING, running, Map.of()
        ));
        Fixture fixture = fixture(workspace, RunCheckpointStore.disabled(), journalStore);

        fixture.service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "direct", "continue"),
                "cli:direct",
                (msg, currentSession, key, raw) -> null
        );

        RunState recovered = journalStore.load("cli:direct", runId).orElseThrow();
        assertEquals(RunStatus.PAUSED, recovered.status());
        assertEquals("process_recovery", recovered.pauseReason());
        assertEquals(
                ToolInvocationStatus.UNKNOWN,
                recovered.toolInvocations().get(running.invocationId()).status()
        );
    }

    @Test
    void prepareInteractiveTurn_reusesDurablyCompletedToolResult(@TempDir Path workspace) {
        FileRunCheckpointStore checkpointStore = new FileRunCheckpointStore(workspace);
        FileRunJournalStore journalStore = new FileRunJournalStore(workspace);
        String runId = "run-completed-before-checkpoint";
        String sessionKey = "cli:direct";
        ToolInvocationRecord running = ToolInvocationRecord.running(
                runId,
                1,
                "call-1",
                "write_file",
                Map.of("path", "result.txt"),
                false,
                "side_effect"
        );
        Map<String, Object> resultMessage = Map.of(
                "role", "tool",
                "tool_call_id", "call-1",
                "name", "write_file",
                "content", "written"
        );
        ToolInvocationRecord completed = running.completed(resultMessage, true, "");
        journalStore.append(RunEvent.create(1, runId, sessionKey, 0,
                RunEventType.RUN_STARTED, RunStatus.CREATED, null, Map.of()));
        journalStore.append(RunEvent.create(2, runId, sessionKey, 1,
                RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null, Map.of()));
        journalStore.append(RunEvent.create(3, runId, sessionKey, 1,
                RunEventType.MODEL_RESPONSE_RECEIVED, RunStatus.WAITING_TOOL, null, Map.of()));
        journalStore.append(RunEvent.create(4, runId, sessionKey, 1,
                RunEventType.TOOL_CALL_STARTED, RunStatus.TOOL_RUNNING, running, Map.of()));
        journalStore.append(RunEvent.create(5, runId, sessionKey, 1,
                RunEventType.TOOL_CALL_COMPLETED, RunStatus.TOOL_RUNNING, completed, Map.of()));

        Map<String, Object> assistant = Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "id", "call-1",
                        "function", Map.of("name", "write_file")
                ))
        );
        checkpointStore.save(RunCheckpoint.fromPayload(sessionKey, Map.of(
                "checkpoint_id", runId + ":1:MODEL_RESPONSE_RECEIVED",
                "run_id", runId,
                "iteration", 1,
                "phase", "MODEL_RESPONSE_RECEIVED",
                "run_messages", List.of(assistant),
                "assistant_message", assistant,
                "pending_tool_calls", List.of(Map.of(
                        "id", "call-1",
                        "function", Map.of("name", "write_file")
                ))
        ), Map.of()));
        Fixture fixture = fixture(workspace, checkpointStore, journalStore);

        PreparedSessionContext prepared = fixture.service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "direct", "continue"),
                sessionKey,
                (msg, currentSession, key, raw) -> null
        );

        assertEquals(2, prepared.session().getMessages().size());
        assertEquals("assistant", prepared.session().getMessages().get(0).get("role"));
        assertEquals("tool", prepared.session().getMessages().get(1).get("role"));
        assertEquals("written", prepared.session().getMessages().get(1).get("content"));
        assertFalse(String.valueOf(prepared.session().getMessages().get(1).get("content")).contains("中断"));
    }

    @Test
    void prepareInteractiveTurn_retriesUnknownReadOnlyTool(@TempDir Path workspace) {
        FileRunCheckpointStore checkpointStore = new FileRunCheckpointStore(workspace);
        FileRunJournalStore journalStore = new FileRunJournalStore(workspace);
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger executions = new AtomicInteger();
        tools.register(new Tool() {
            @Override
            public String getName() {
                return "read_file";
            }

            @Override
            public String getDescription() {
                return "read";
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public Object execute(Map<String, Object> params) {
                executions.incrementAndGet();
                return Map.of("content", "recovered content");
            }
        });
        String runId = "run-read-recovery";
        String sessionKey = "cli:direct";
        ToolInvocationRecord running = ToolInvocationRecord.running(
                runId, 1, "call-1", "read_file",
                Map.of("path", "hello.txt"), true, "read_only"
        );
        journalStore.append(RunEvent.create(1, runId, sessionKey, 0,
                RunEventType.RUN_STARTED, RunStatus.CREATED, null, Map.of()));
        journalStore.append(RunEvent.create(2, runId, sessionKey, 1,
                RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null, Map.of()));
        journalStore.append(RunEvent.create(3, runId, sessionKey, 1,
                RunEventType.MODEL_RESPONSE_RECEIVED, RunStatus.WAITING_TOOL, null, Map.of()));
        journalStore.append(RunEvent.create(4, runId, sessionKey, 1,
                RunEventType.TOOL_CALL_STARTED, RunStatus.TOOL_RUNNING, running, Map.of()));

        Map<String, Object> pendingCall = Map.of(
                "id", "call-1",
                "type", "function",
                "function", Map.of(
                        "name", "read_file",
                        "arguments", "{\"path\":\"hello.txt\"}"
                )
        );
        Map<String, Object> assistant = Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(pendingCall)
        );
        checkpointStore.save(RunCheckpoint.fromPayload(sessionKey, Map.of(
                "checkpoint_id", runId + ":1:MODEL_RESPONSE_RECEIVED",
                "run_id", runId,
                "journal_run_id", runId,
                "iteration", 1,
                "phase", "MODEL_RESPONSE_RECEIVED",
                "run_messages", List.of(assistant),
                "assistant_message", assistant,
                "pending_tool_calls", List.of(pendingCall)
        ), Map.of()));
        Fixture fixture = fixture(
                workspace,
                checkpointStore,
                journalStore,
                new RunRecoveryCoordinator(journalStore, tools)
        );

        PreparedSessionContext prepared = fixture.service.prepareInteractiveTurn(
                new InboundMessage("cli", "user", "direct", "continue"),
                sessionKey,
                (msg, currentSession, key, raw) -> null
        );

        assertEquals(1, executions.get());
        assertEquals(2, prepared.session().getMessages().size());
        assertTrue(String.valueOf(prepared.session().getMessages().get(1).get("content"))
                .contains("recovered content"));
        RunState recovered = journalStore.load(sessionKey, runId).orElseThrow();
        assertEquals(RunStatus.PAUSED, recovered.status());
        assertEquals(ToolInvocationStatus.SUCCEEDED,
                recovered.toolInvocations().get(running.invocationId()).status());
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
        return fixture(workspace, RunCheckpointStore.disabled());
    }

    private Fixture fixture(Path workspace, RunCheckpointStore checkpointStore) {
        return fixture(workspace, checkpointStore, RunJournalStore.disabled());
    }

    private Fixture fixture(
            Path workspace,
            RunCheckpointStore checkpointStore,
            RunJournalStore journalStore
    ) {
        return fixture(workspace, checkpointStore, journalStore, null);
    }

    private Fixture fixture(
            Path workspace,
            RunCheckpointStore checkpointStore,
            RunJournalStore journalStore,
            RunRecoveryCoordinator recoveryCoordinator
    ) {
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
        return new Fixture(sessions, new SessionPreparationService(
                sessions,
                autoCompact,
                consolidator,
                checkpointStore,
                journalStore,
                recoveryCoordinator
        ));
    }

    private record Fixture(SessionManager sessions, SessionPreparationService service) {
    }
}
