package ricbot.tool.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.PendingToolCall;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExecToolTest {

    @Test
    void largeOutput_doesNotDeadlockOrFalseTimeout(@TempDir Path workspace) {
        ExecTool tool = new ExecTool(5, workspace.toString(), null, null, true, "", "", List.of());

        String result = tool.execute("i=0; while [ \"$i\" -lt 5000 ]; do echo line-$i; i=$((i+1)); done", null, 5);

        assertFalse(result.startsWith("错误：命令执行超时"), result);
        assertTrue(result.contains("line-0"), result);
    }

    @Test
    void hugeOutput_isDrainedButNotFullyCaptured(@TempDir Path workspace) {
        ExecTool tool = new ExecTool(5, workspace.toString(), null, null, true, "", "", List.of());

        String result = tool.execute("i=0; while [ \"$i\" -lt 20000 ]; do echo line-$i; i=$((i+1)); done", null, 5);

        assertFalse(result.startsWith("错误：命令执行超时"), result);
        assertTrue(result.contains("line-0"), result);
        assertTrue(result.contains("已截断") || result.contains("输出过长"), result);
    }

    @Test
    void longRunningCommand_stillTimesOut(@TempDir Path workspace) {
        ExecTool tool = new ExecTool(1, workspace.toString(), null, null, true, "", "", List.of());

        String result = tool.execute("sleep 2", null, 1);

        assertTrue(result.startsWith("错误：命令执行超时"), result);
    }

    @Test
    void riskAnalyzerBlocksDangerousCommandsAndRequestsApproval(@TempDir Path workspace) {
        ApprovalService approvalService = new ApprovalService();
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

        String blocked = tool.execute("sudo rm file", null, 5);
        assertTrue(blocked.contains("风险策略拒绝"), blocked);
        assertTrue(blocked.contains("riskLevel: BLOCKED"), blocked);

        String high = tool.execute("rm build.log", null, 5);
        assertTrue(high.contains("需要审批后才能执行"), high);
        assertTrue(high.contains("requestId:"), high);
        assertTrue(high.contains("riskLevel: HIGH"), high);

        String medium = tool.execute("mkdir reports", null, 5);
        assertTrue(medium.contains("riskLevel: MEDIUM"), medium);

        String safe = tool.execute("pwd", null, 5);
        assertFalse(safe.contains("需要审批"), safe);
    }

    @Test
    void highRiskCommandCreatesPendingCallAndApprovedExecutionBypassesRiskGate(@TempDir Path workspace) {
        ApprovalService approvalService = new ApprovalService();
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

        String gated = String.valueOf(registry.execute("exec", java.util.Map.of("command", "touch approved.txt")));
        String requestId = requestId(gated);
        assertTrue(gated.contains("riskLevel: MEDIUM"), gated);

        approvalService.approve(requestId);
        PendingToolCall call = approvalService.consumeApprovedToolCall(requestId);
        String result = String.valueOf(registry.executeApproved(call.toolName(), call.arguments()));

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
