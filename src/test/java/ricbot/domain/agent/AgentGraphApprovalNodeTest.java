package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.graph.GraphExecutionStatus;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRiskDecision;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.api.ToolEffectPolicy;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentGraphApprovalNodeTest {
    @Test
    void sideEffectPreflightPausesGraphBeforeToolExecution(@TempDir Path workspace) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            public String getName() { return "dangerous_write"; }
            public String getDescription() { return "writes"; }
            public ToolEffectPolicy effectPolicy() {
                return ToolEffectPolicy.atMostOnce(java.time.Duration.ofSeconds(30),
                        ToolEffectPolicy.Concurrency.EXCLUSIVE_WORKSPACE, ToolEffectPolicy.Approval.RISK_BASED);
            }
            public ToolRiskDecision assessRisk(Map<String, Object> params) {
                return ToolRiskDecision.from(RiskAssessment.of(CommandRiskLevel.HIGH,
                        List.of("test write"), "write", getName(), List.of("file.txt")));
            }
            public Object execute(Map<String, Object> params) {
                executions.incrementAndGet();
                return Map.of("ok", true);
            }
        });
        AtomicInteger modelCalls = new AtomicInteger();
        LLMProvider provider = new LLMProvider("key", "http://localhost") {
            public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> definitions,
                                    String model, Integer maxTokens, Double temperature,
                                    String reasoningEffort, Object toolChoice) {
                if (modelCalls.incrementAndGet() == 1) {
                    return new LLMResponse().setContent("").setFinishReason("tool_calls")
                            .setToolCalls(List.of(new ToolCallRequest("call-write", "dangerous_write", Map.of())));
                }
                return new LLMResponse("done").setFinishReason("stop");
            }
        };
        SqliteRuntimeStore runtimeStore = new SqliteRuntimeStore(workspace);
        ApprovalService approvals = new ApprovalService(runtimeStore.approvalStore());
        AgentRunResult result = new GraphRunService(provider).run(new AgentRunSpec()
                .setInitialMessages(List.of(Map.of("role", "user", "content", "write")))
                .setTools(tools).setModel("fake").setWorkspace(workspace).setSessionKey("cli:test")
                .setApprovalService(approvals).setSideEffectStore(runtimeStore.sideEffectStore())
                .setMaxIterations(3));

        assertEquals("approval_required", result.getStopReason());
        assertEquals(0, executions.get());
        ApprovalRequest request = approvals.listPending().get(0);
        assertEquals(result.getRunId(), request.binding().runId());
        assertEquals("TOOL", request.binding().actionType());
        assertEquals(SideEffectStatus.AWAITING_APPROVAL,
                runtimeStore.sideEffectStore().load(request.binding().idempotencyKey()).orElseThrow().status());
        var graph = runtimeStore.loadCheckpoint(result.getRunId()).orElseThrow();
        assertEquals(GraphExecutionStatus.PAUSED, graph.status());
        assertEquals(AgentNodeType.APPROVAL.name(), graph.nodeId());

        approvals.approve(request.requestId());
        var resumed = new AgentApprovalGraphResumeService(workspace, provider, "fake", tools, approvals)
                .resume(request.requestId());
        assertEquals(GraphExecutionStatus.COMPLETED, resumed.status());
        assertEquals(1, executions.get());
        assertTrue(approvals.find(request.requestId()).consumed());
        assertEquals("done", ((Map<?, ?>) resumed.channels().get("modelResponse")).get("content"));
    }
}
