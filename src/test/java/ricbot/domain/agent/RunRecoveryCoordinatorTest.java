package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunRecoveryCoordinatorTest {

    @Test
    void retriesUnknownReadOnlyToolWhenArgumentsStillMatch(@TempDir Path workspace) {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool("read_file", true, executions, Map.of("content", "hello")));
        FileRunJournalStore journal = new FileRunJournalStore(workspace);
        ToolInvocationRecord invocation = interruptedInvocation(
                journal,
                "run-1",
                "cli:direct",
                "read_file",
                true,
                Map.of("path", "hello.txt")
        );
        RunState paused = journal.pauseLatestInterrupted("cli:direct", "process_recovery").orElseThrow();
        RunRecoveryCoordinator coordinator = new RunRecoveryCoordinator(journal, tools);

        Map<String, ToolRecoveryResolution> resolutions = coordinator.resolve(
                Map.of("journal_run_id", "run-1"),
                java.util.Optional.of(paused),
                List.of(toolCall("call-1", "read_file", "{\"path\":\"hello.txt\"}"))
        );

        assertEquals(1, executions.get());
        ToolRecoveryResolution resolution = resolutions.get("call-1");
        assertEquals(ToolRecoveryAction.RETRY_READ_ONLY, resolution.action());
        assertTrue(resolution.hasResult());
        assertTrue(String.valueOf(resolution.resultMessage().get("content")).contains("hello"));

        RunState recovered = journal.load("cli:direct", "run-1").orElseThrow();
        assertEquals(RunStatus.PAUSED, recovered.status());
        assertEquals(
                ToolInvocationStatus.SUCCEEDED,
                recovered.toolInvocations().get(invocation.invocationId()).status()
        );
        assertEquals(
                List.of(
                        RunEventType.TOOL_RETRY_STARTED,
                        RunEventType.TOOL_RETRY_COMPLETED,
                        RunEventType.RUN_PAUSED
                ),
                journal.events("cli:direct", "run-1", 5).stream().map(RunEvent::type).toList()
        );
    }

    @Test
    void refusesUnknownSideEffectTool(@TempDir Path workspace) {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool("write_file", false, executions, "written"));
        FileRunJournalStore journal = new FileRunJournalStore(workspace);
        interruptedInvocation(
                journal,
                "run-1",
                "cli:direct",
                "write_file",
                false,
                Map.of("path", "result.txt")
        );
        RunState paused = journal.pauseLatestInterrupted("cli:direct", "process_recovery").orElseThrow();

        ToolRecoveryResolution resolution = new RunRecoveryCoordinator(journal, tools).resolve(
                Map.of("journal_run_id", "run-1"),
                java.util.Optional.of(paused),
                List.of(toolCall("call-1", "write_file", "{\"path\":\"result.txt\"}"))
        ).get("call-1");

        assertEquals(0, executions.get());
        assertEquals(ToolRecoveryAction.REQUIRE_CONFIRMATION, resolution.action());
        assertTrue(resolution.reason().contains("side-effect"));
        assertEquals(5, journal.events("cli:direct", "run-1", 0).size());
    }

    @Test
    void refusesReadOnlyRetryWhenCheckpointArgumentsWereChanged(@TempDir Path workspace) {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool("read_file", true, executions, "content"));
        FileRunJournalStore journal = new FileRunJournalStore(workspace);
        interruptedInvocation(
                journal,
                "run-1",
                "cli:direct",
                "read_file",
                true,
                Map.of("path", "original.txt")
        );
        RunState paused = journal.pauseLatestInterrupted("cli:direct", "process_recovery").orElseThrow();

        ToolRecoveryResolution resolution = new RunRecoveryCoordinator(journal, tools).resolve(
                Map.of("journal_run_id", "run-1"),
                java.util.Optional.of(paused),
                List.of(toolCall("call-1", "read_file", "{\"path\":\"changed.txt\"}"))
        ).get("call-1");

        assertEquals(0, executions.get());
        assertEquals(ToolRecoveryAction.REQUIRE_CONFIRMATION, resolution.action());
        assertTrue(resolution.reason().contains("arguments"));
    }

    private static ToolInvocationRecord interruptedInvocation(
            FileRunJournalStore journal,
            String runId,
            String sessionKey,
            String toolName,
            boolean readOnly,
            Map<String, Object> arguments
    ) {
        ToolInvocationRecord invocation = ToolInvocationRecord.running(
                runId, 1, "call-1", toolName, arguments, readOnly,
                readOnly ? "read_only" : "side_effect"
        );
        journal.append(event(1, runId, sessionKey, RunEventType.RUN_STARTED, RunStatus.CREATED, null));
        journal.append(event(2, runId, sessionKey, RunEventType.MODEL_REQUESTED, RunStatus.MODEL_RUNNING, null));
        journal.append(event(3, runId, sessionKey, RunEventType.MODEL_RESPONSE_RECEIVED, RunStatus.WAITING_TOOL, null));
        journal.append(event(4, runId, sessionKey, RunEventType.TOOL_CALL_STARTED, RunStatus.TOOL_RUNNING, invocation));
        return invocation;
    }

    private static RunEvent event(
            long sequence,
            String runId,
            String sessionKey,
            RunEventType type,
            RunStatus status,
            ToolInvocationRecord invocation
    ) {
        return RunEvent.create(sequence, runId, sessionKey, 1, type, status, invocation, Map.of());
    }

    private static Map<String, Object> toolCall(String id, String name, String arguments) {
        return Map.of(
                "id", id,
                "type", "function",
                "function", Map.of("name", name, "arguments", arguments)
        );
    }

    private static Tool tool(
            String name,
            boolean readOnly,
            AtomicInteger executions,
            Object result
    ) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public boolean isReadOnly() {
                return readOnly;
            }

            @Override
            public Object execute(Map<String, Object> params) {
                executions.incrementAndGet();
                return result;
            }
        };
    }
}
