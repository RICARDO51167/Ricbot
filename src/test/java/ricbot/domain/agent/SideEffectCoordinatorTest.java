package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.dto.SideEffectExecutionIdentity;
import ricbot.domain.agent.dto.SideEffectOutcome;
import ricbot.domain.agent.dto.SideEffectRecord;
import ricbot.domain.agent.dto.ToolInvocationRecord;
import ricbot.domain.agent.eump.SideEffectStatus;
import ricbot.domain.agent.interfacep.SideEffectStore;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolEffectPolicy;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SideEffectCoordinatorTest {
    @Test
    void reusesDurablyCompletedEffectWithoutExecutingAgain(@TempDir Path workspace) {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = registry(new CountingTool(executions));
        SideEffectCoordinator coordinator = new SideEffectCoordinator(new SqliteRuntimeStore(workspace).sideEffectStore());

        SideEffectOutcome first = coordinator.execute(
                tools, "session", "key-1", "write", Map.of("value", "x"), false, ignored -> true);
        SideEffectOutcome second = coordinator.execute(
                tools, "session", "key-1", "write", Map.of("value", "x"), false, ignored -> true);

        assertFalse(first.reused());
        assertTrue(second.reused());
        assertEquals(1, executions.get());
    }

    @Test
    void unknownEffectRequiresExplicitRetryAuthorization(@TempDir Path workspace) {
        SideEffectStore store = new SqliteRuntimeStore(workspace).sideEffectStore();
        SideEffectCoordinator coordinator = new SideEffectCoordinator(store);
        ToolRegistry tools = registry(new CountingTool(new AtomicInteger()));
        Map<String, Object> args = Map.of("value", "x");
        SideEffectRecord reserved = reserveUnknown(store, "key-2", args);

        assertThrows(SideEffectConfirmationRequiredException.class, () -> coordinator.execute(
                tools, "session", reserved.idempotencyKey(), "write", args, false, ignored -> true));

        coordinator.authorizeRetry(reserved.idempotencyKey(), "approval-7");
        SideEffectOutcome retried = coordinator.execute(
                tools, "session", reserved.idempotencyKey(), "write", args, false, ignored -> true);
        assertEquals(SideEffectStatus.SUCCEEDED, retried.record().status());
        assertEquals("approval-7", retried.record().confirmationId());
    }

    @Test
    void crashAfterReservationBeforeExecutionIsSafeToResume(@TempDir Path workspace) {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = registry(new CountingTool(executions));
        SideEffectStore store = new SqliteRuntimeStore(workspace).sideEffectStore();
        Map<String, Object> args = Map.of("value", "x");
        SideEffectRecord reserved = SideEffectRecord.reserved("reserved-only", "run", "session", "",
                "activation", "write", ToolInvocationRecord.argumentsDigest(args), args);
        store.claim(reserved);

        SideEffectOutcome recovered = new SideEffectCoordinator(store).execute(tools,
                new SideEffectExecutionIdentity("run", "session", "", "activation"),
                reserved.idempotencyKey(), reserved.toolName(), args, policy(), ignored -> true, ignored -> false);

        assertEquals(1, executions.get());
        assertEquals(SideEffectStatus.SUCCEEDED, recovered.record().status());
    }

    @Test
    void retryRequestIsBoundAndAudited(@TempDir Path workspace) {
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(workspace);
        TraceStore traces = new TraceStore(workspace);
        SideEffectStore store = new AuditedSideEffectStore(runtime.sideEffectStore(), traces);
        ApprovalService approvals = new ApprovalService(runtime.approvalStore(), traces);
        SideEffectApplicationService service = new SideEffectApplicationService(store, approvals);
        SideEffectRecord reserved = reserveUnknown(store, "uncertain", Map.of("value", "x"));

        ApprovalRequest retry = service.requestRetry(reserved.idempotencyKey());

        assertEquals(SideEffectApplicationService.RETRY_ACTION, retry.binding().actionType());
        assertEquals("run-2", retry.binding().runId());
        assertTrue(traces.loadEvents(traces.traceIdForSession("session")).stream()
                .map(event -> event.type()).anyMatch(TraceEventType.SIDE_EFFECT_RESERVED::equals));
    }

    private static SideEffectRecord reserveUnknown(SideEffectStore store, String key, Map<String, Object> args) {
        SideEffectRecord reserved = SideEffectRecord.reserved(
                key, "run-2", "session", "", "activation-2", "write",
                ToolInvocationRecord.argumentsDigest(args), args);
        store.claim(reserved);
        return store.transition(reserved.clearLease(SideEffectStatus.UNKNOWN, null, ""), 0,
                java.util.Set.of(SideEffectStatus.RESERVED));
    }

    private static ToolRegistry registry(Tool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry;
    }

    private static ToolEffectPolicy policy() {
        return ToolEffectPolicy.atMostOnce(java.time.Duration.ofSeconds(30),
                ToolEffectPolicy.Concurrency.SERIAL_PER_RUN, ToolEffectPolicy.Approval.RISK_BASED);
    }

    private static final class CountingTool extends Tool {
        private final AtomicInteger executions;
        private CountingTool(AtomicInteger executions) { this.executions = executions; }
        public String getName() { return "write"; }
        public String getDescription() { return "test"; }
        public Object execute(Map<String, Object> params, ToolExecutionContext context) {
            return Map.of("ok", true, "count", executions.incrementAndGet(), "approved", context.approved());
        }
        public Object execute(Map<String, Object> params) {
            return execute(params, ToolExecutionContext.normal());
        }
        @Override public ToolEffectPolicy effectPolicy() { return policy(); }
    }
}

