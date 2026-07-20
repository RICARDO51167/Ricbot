package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SideEffectCoordinatorTest {
    @Test
    void reusesDurablyCompletedEffectWithoutExecutingAgain(@TempDir Path workspace) {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = registry(new CountingTool(executions, false));
        SideEffectCoordinator coordinator = new SideEffectCoordinator(new FileSideEffectStore(workspace));

        SideEffectOutcome first = coordinator.execute(
                tools, "session", "key-1", "write", Map.of("value", "x"), false, ignored -> true);
        SideEffectOutcome second = coordinator.execute(
                tools, "session", "key-1", "write", Map.of("value", "x"), false, ignored -> true);

        assertFalse(first.reused());
        assertTrue(second.reused());
        assertEquals(1, executions.get());
        assertEquals(first.result(), second.result());
    }

    @Test
    void uncertainEffectRequiresExplicitRetryAuthorization(@TempDir Path workspace) {
        FileSideEffectStore store = new FileSideEffectStore(workspace);
        SideEffectCoordinator coordinator = new SideEffectCoordinator(store);
        ToolRegistry tools = registry(new CountingTool(new AtomicInteger(), false));
        Map<String, Object> args = Map.of("value", "x");
        store.claim(SideEffectRecord.reserved(
                "key-2", "session", "write", ToolInvocationRecord.argumentsDigest(args)));

        assertThrows(SideEffectConfirmationRequiredException.class, () -> coordinator.execute(
                tools, "session", "key-2", "write", args, false, ignored -> true));

        coordinator.authorizeRetry("key-2", "approval-7");
        SideEffectOutcome retried = coordinator.execute(
                tools, "session", "key-2", "write", args, false, ignored -> true);
        assertEquals(SideEffectStatus.SUCCEEDED, retried.record().status());
        assertEquals("approval-7", retried.record().confirmationId());
    }

    @Test
    void compensatesOnlyMatchingSuccessfulEffect(@TempDir Path workspace) {
        AtomicInteger executions = new AtomicInteger();
        CountingTool tool = new CountingTool(executions, true);
        ToolRegistry tools = registry(tool);
        SideEffectCoordinator coordinator = new SideEffectCoordinator(new FileSideEffectStore(workspace));
        Map<String, Object> args = Map.of("value", "x");
        coordinator.execute(tools, "session", "key-3", "write", args, false, ignored -> true);

        assertThrows(IllegalArgumentException.class, () ->
                coordinator.compensate(tools, "key-3", Map.of("value", "other"), "approval-9"));

        SideEffectRecord compensated = coordinator.compensate(tools, "key-3", args, "approval-8");

        assertEquals(SideEffectStatus.COMPENSATED, compensated.status());
        assertEquals(1, tool.compensations.get());
    }

    @Test
    void retryAndCompensationRequireBoundOneShotApprovals(@TempDir Path workspace) {
        TraceStore traces = new TraceStore(workspace);
        SideEffectStore store = new AuditedSideEffectStore(new FileSideEffectStore(workspace), traces);
        ApprovalService approvals = new ApprovalService(traces);
        SideEffectApplicationService service = new SideEffectApplicationService(store, approvals);
        CountingTool tool = new CountingTool(new AtomicInteger(), true);
        ToolRegistry tools = registry(tool);
        Map<String, Object> args = Map.of("value", "x");
        store.claim(SideEffectRecord.reserved(
                "uncertain", "session", "write", ToolInvocationRecord.argumentsDigest(args)));

        ApprovalRequest retry = service.requestRetry("uncertain");
        assertThrows(IllegalStateException.class, () -> service.applyApprovedRetry(retry.requestId()));
        approvals.approve(retry.requestId());
        assertEquals(SideEffectStatus.RETRY_AUTHORIZED,
                service.applyApprovedRetry(retry.requestId()).status());
        assertThrows(IllegalStateException.class, () -> service.applyApprovedRetry(retry.requestId()));

        new SideEffectCoordinator(store).execute(
                tools, "session", "uncertain", "write", args, false, ignored -> true);
        ApprovalRequest compensation = service.requestCompensation("uncertain", args);
        approvals.approve(compensation.requestId());
        assertEquals(SideEffectStatus.COMPENSATED,
                service.applyApprovedCompensation(tools, compensation.requestId()).status());

        List<TraceEventType> types = traces.loadEvents(traces.traceIdForSession("session")).stream()
                .map(event -> event.type()).toList();
        assertTrue(types.contains(TraceEventType.SIDE_EFFECT_RESERVED));
        assertTrue(types.contains(TraceEventType.SIDE_EFFECT_RETRY_AUTHORIZED));
        assertTrue(types.contains(TraceEventType.SIDE_EFFECT_SUCCEEDED));
        assertTrue(types.contains(TraceEventType.SIDE_EFFECT_COMPENSATED));
    }

    private static ToolRegistry registry(Tool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry;
    }

    private static final class CountingTool extends Tool {
        private final AtomicInteger executions;
        private final AtomicInteger compensations = new AtomicInteger();
        private final boolean compensatable;

        private CountingTool(AtomicInteger executions, boolean compensatable) {
            this.executions = executions;
            this.compensatable = compensatable;
        }
        public String getName() { return "write"; }
        public String getDescription() { return "test"; }
        public Object execute(Map<String, Object> params, ToolExecutionContext context) {
            return Map.of("ok", true, "count", executions.incrementAndGet(),
                    "idempotency_key", context.idempotencyKey());
        }
        public boolean supportsCompensation() { return compensatable; }
        public Object compensate(Map<String, Object> params, Object result, ToolExecutionContext context) {
            return Map.of("ok", true, "compensations", compensations.incrementAndGet());
        }
    }
}
