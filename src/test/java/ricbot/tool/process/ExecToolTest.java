package ricbot.tool.process;

import org.junit.jupiter.api.Test;
import ricbot.testsupport.InMemoryApprovalRequestStore;
import ricbot.tool.api.Tool;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.PendingToolCall;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.api.ToolResult;
import ricbot.tool.api.ToolInvocation;
import ricbot.tool.api.ToolExecutionContext;
import ricbot.tool.api.ToolChunkSink;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExecToolTest {

    @Test
    void largeOutput_doesNotDeadlockOrFalseTimeout(@TempDir Path workspace) throws Exception {
        ExecTool tool = new ExecTool(5, workspace.toString(), null, null, true, "", "", List.of());

        String result = String.valueOf(tool.execute(java.util.Map.of(
                "command", "i=0; while [ \"$i\" -lt 5000 ]; do echo line-$i; i=$((i+1)); done", "timeout", 5)));

        assertFalse(result.startsWith("错误：命令执行超时"), result);
        assertTrue(result.contains("line-0"), result);
    }

    @Test
    void hugeOutput_isPreservedForRuntimeOffload(@TempDir Path workspace) throws Exception {
        ExecTool tool = new ExecTool(5, workspace.toString(), null, null, true, "", "", List.of());

        String result = String.valueOf(tool.execute(java.util.Map.of(
                "command", "i=0; while [ \"$i\" -lt 20000 ]; do echo line-$i; i=$((i+1)); done", "timeout", 5)));

        assertFalse(result.startsWith("错误：命令执行超时"), result);
        assertTrue(result.contains("line-0"), result);
        assertTrue(result.contains("line-19999"));
        assertFalse(result.contains("已截断") || result.contains("输出过长"));
    }

    @Test
    void longRunningCommand_stillTimesOut(@TempDir Path workspace) throws Exception {
        ExecTool tool = new ExecTool(1, workspace.toString(), null, null, true, "", "", List.of());

        String result = String.valueOf(tool.execute(java.util.Map.of("command", "sleep 2", "timeout", 1)));

        assertTrue(result.startsWith("错误：命令执行超时"), result);
    }

    @Test
    void riskAnalyzerBlocksDangerousCommandsAndRequestsApproval(@TempDir Path workspace) throws Exception {
        ApprovalService approvalService = new ApprovalService(new InMemoryApprovalRequestStore());
        ExecTool tool = new ExecTool(
                5,
                workspace.toString(),
                List.of(),
                null,
                true,
                "",
                "",
                List.of(),
                new CommandRiskAnalyzer(workspace),
                approvalService
        );

        String blocked = String.valueOf(tool.execute(java.util.Map.of("command", "sudo rm file", "timeout", 5)));
        assertTrue(blocked.contains("风险策略拒绝"), blocked);
        assertTrue(blocked.contains("riskLevel: BLOCKED"), blocked);

        String high = String.valueOf(tool.execute(java.util.Map.of("command", "rm build.log", "timeout", 5)));
        assertTrue(high.contains("需要审批后才能执行"), high);
        assertTrue(high.contains("requestId:"), high);
        assertTrue(high.contains("riskLevel: HIGH"), high);

        String medium = String.valueOf(tool.execute(java.util.Map.of("command", "mkdir reports", "timeout", 5)));
        assertTrue(medium.contains("riskLevel: MEDIUM"), medium);

        String safe = String.valueOf(tool.execute(java.util.Map.of("command", "pwd", "timeout", 5)));
        assertFalse(safe.contains("需要审批"), safe);
    }

    @Test
    void highRiskCommandCreatesPendingCallAndApprovedExecutionBypassesRiskGate(@TempDir Path workspace) throws Exception {
        ApprovalService approvalService = new ApprovalService(new InMemoryApprovalRequestStore());
        ExecTool tool = new ExecTool(
                5,
                workspace.toString(),
                List.of(),
                null,
                true,
                "",
                "",
                List.of(),
                new CommandRiskAnalyzer(workspace),
                approvalService
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        String gated = String.valueOf(tool.execute(java.util.Map.of("command", "touch approved.txt")));
        String requestId = requestId(gated);
        assertTrue(gated.contains("riskLevel: MEDIUM"), gated);

        approvalService.approve(requestId);
        PendingToolCall call = approvalService.consumeApprovedToolCall(requestId);
        ToolResult executed;
        try { executed = tool.execute(new ToolInvocation("approved", call.toolName(), call.arguments()),
                ToolExecutionContext.approvedContext(), ToolChunkSink.discard()); }
        catch (Exception failure) { throw new AssertionError(failure); }
        String result = String.valueOf(((ToolResult.Success) executed).value());

        assertFalse(result.contains("需要审批后才能执行"), result);
        assertTrue(java.nio.file.Files.exists(workspace.resolve("approved.txt")));
        assertThrows(IllegalStateException.class, () -> approvalService.consumeApprovedToolCall(requestId));
    }

    private static String requestId(String text) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("requestId:")) {
                return line.substring("requestId:".length()).trim();
            }
        }
        throw new AssertionError("missing requestId in: " + text);
    }
}
