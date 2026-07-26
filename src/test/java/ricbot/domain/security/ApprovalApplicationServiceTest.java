package ricbot.domain.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.trace.TraceRecorder;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalApplicationServiceTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void listPending_recordsApprovalTrace() {
        List<Map<String, Object>> events = new ArrayList<>();
        ApprovalService approvalService = new ApprovalService(Duration.ofMinutes(30), FIXED_CLOCK);
        approvalService.createRequest(assessment("list"));
        ApprovalApplicationService app = new ApprovalApplicationService(
                approvalService,
                null,
                null,
                TraceRecorder.forRunEvents(events)
        );

        List<ApprovalRequest> pending = app.listPending();

        assertEquals(1, pending.size());
        assertTrue(events.stream().anyMatch(event ->
                "approval_list_pending".equals(event.get("type"))
                        && Integer.valueOf(1).equals(event.get("pendingCount"))));
    }

    @Test
    void approveOnly_recordsApprovalTraceWithExecutedFalse() {
        List<Map<String, Object>> events = new ArrayList<>();
        ApprovalService approvalService = new ApprovalService(Duration.ofMinutes(30), FIXED_CLOCK);
        ApprovalRequest request = approvalService.createRequest(assessment("approve only"));
        ApprovalApplicationService app = new ApprovalApplicationService(
                approvalService,
                null,
                null,
                TraceRecorder.forRunEvents(events)
        );

        ApprovalApplicationService.ApprovalActionResult result = app.approveOnly(request.requestId());

        assertFalse(result.executed());
        assertTrue(events.stream().anyMatch(event ->
                "approval_approve_only".equals(event.get("type"))
                        && request.requestId().equals(event.get("approvalId"))
                        && Boolean.FALSE.equals(event.get("executed"))
                        && "approved".equals(event.get("status"))));
    }

    @Test
    void approvalApplicationHasNoExecuteBypass() {
        assertThrows(NoSuchMethodException.class,
                () -> ApprovalApplicationService.class.getMethod("approveAndExecute", String.class));
    }

    @Test
    void reject_recordsApprovalTrace() {
        List<Map<String, Object>> events = new ArrayList<>();
        ApprovalService approvalService = new ApprovalService(Duration.ofMinutes(30), FIXED_CLOCK);
        ApprovalRequest request = approvalService.createRequest(assessment("reject"));
        ApprovalApplicationService app = new ApprovalApplicationService(
                approvalService,
                null,
                null,
                TraceRecorder.forRunEvents(events)
        );

        ApprovalApplicationService.ApprovalActionResult result = app.reject(request.requestId());

        assertFalse(result.executed());
        assertTrue(events.stream().anyMatch(event ->
                "approval_reject".equals(event.get("type"))
                        && request.requestId().equals(event.get("approvalId"))
                        && "rejected".equals(event.get("status"))));
    }

    @Test
    void expiredApproval_recordsApprovalErrorTraceAndStillThrows() {
        List<Map<String, Object>> events = new ArrayList<>();
        ApprovalService approvalService = new ApprovalService(Duration.ZERO, FIXED_CLOCK);
        ApprovalRequest request = approvalService.createRequest(assessment("expired"));
        ApprovalApplicationService app = new ApprovalApplicationService(
                approvalService,
                null,
                null,
                TraceRecorder.forRunEvents(events)
        );

        assertThrows(IllegalStateException.class, () -> app.approveOnly(request.requestId()));

        assertTrue(events.stream().anyMatch(event ->
                "approval_expired".equals(event.get("type"))
                        && request.requestId().equals(event.get("approvalId"))
                        && "approval_expired".equals(event.get("errorType"))));
    }

    @Test
    void repeatedApproval_recordsApprovalErrorTrace() {
        List<Map<String, Object>> events = new ArrayList<>();
        ApprovalService approvalService = new ApprovalService(Duration.ofMinutes(30), FIXED_CLOCK);
        ApprovalRequest request = approvalService.createRequest(assessment("repeat"));
        ApprovalApplicationService app = new ApprovalApplicationService(
                approvalService,
                null,
                null,
                TraceRecorder.forRunEvents(events)
        );
        app.approveOnly(request.requestId());

        assertThrows(IllegalStateException.class, () -> app.approveOnly(request.requestId()));

        assertTrue(events.stream().anyMatch(event ->
                "approval_repeated".equals(event.get("type"))
                        && request.requestId().equals(event.get("approvalId"))
                        && "approval_repeated".equals(event.get("errorType"))));
    }

    @Test
    void rejectedApprovalConsume_recordsApprovalErrorTrace() {
        List<Map<String, Object>> events = new ArrayList<>();
        ApprovalService approvalService = new ApprovalService(Duration.ofMinutes(30), FIXED_CLOCK);
        ApprovalRequest request = approvalService.createRequest(
                assessment("rejected consume"),
                "echo_tool",
                Map.of("value", "no"),
                "session-1"
        );
        ApprovalApplicationService app = new ApprovalApplicationService(
                approvalService,
                new ToolRegistry(),
                null,
                TraceRecorder.forRunEvents(events)
        );
        app.reject(request.requestId());

        assertThrows(IllegalStateException.class, () -> app.approveOnly(request.requestId()));
    }

    private static RiskAssessment assessment(String reason) {
        return RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of(reason), "", "write_file", List.of("file.txt"));
    }

    private static Tool tool(String name, Object result) {
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
            public Object execute(Map<String, Object> params) {
                return result;
            }
        };
    }
}
