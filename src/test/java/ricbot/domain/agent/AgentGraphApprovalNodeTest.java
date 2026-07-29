package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.eump.AgentNodeType;
import ricbot.domain.agent.eump.SideEffectStatus;
import ricbot.domain.agent.graph.enump.GraphExecutionStatus;
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
import ricbot.application.runtime.AgentRuntimeExecutionService;
import ricbot.application.runtime.LocalAgentRuntime;
import ricbot.application.runtime.RuntimeDriver;

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
        AgentGraphFactory graphs = new AgentGraphFactory(provider, null, false, tools,
                runtimeStore.sideEffectStore(), approvals);
        LocalAgentRuntime agentRuntime = new LocalAgentRuntime(runtimeStore, new RuntimeDriver(), graphs);
        AgentRunResult result = new AgentRuntimeExecutionService(agentRuntime, graphs).run(new AgentRunSpec()
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

        approvals.acceptCommitted(runtimeStore.decideApprovalAndSignal(request.requestId(), true));
        var resumed = agentRuntime.resume(request.binding().runId()).state();
        assertEquals(GraphExecutionStatus.COMPLETED, resumed.status());
        assertEquals(1, executions.get());
        assertTrue(approvals.find(request.requestId()).consumed());
        assertEquals("done", ((Map<?, ?>) resumed.channels().get("modelResponse")).get("content"));
    }

    @Test
    void unknownAtMostOnceResultImmediatelyPausesForBoundRetryApproval(@TempDir Path workspace) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            public String getName() { return "uncertain_write"; }
            public String getDescription() { return "uncertain external write"; }
            public ToolEffectPolicy effectPolicy() {
                return ToolEffectPolicy.atMostOnce(java.time.Duration.ofSeconds(30),
                        ToolEffectPolicy.Concurrency.SERIAL_PER_RUN, ToolEffectPolicy.Approval.NEVER);
            }
            public Object execute(Map<String, Object> params) {
                executions.incrementAndGet();
                throw new IllegalStateException("connection lost after submit");
            }
        });
        LLMProvider provider = toolCallingProvider("uncertain_write");
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        ApprovalService approvals = new ApprovalService(store.approvalStore());
        AgentGraphFactory graphs = new AgentGraphFactory(provider, null, false, tools,
                store.sideEffectStore(), approvals);
        try (LocalAgentRuntime runtime = new LocalAgentRuntime(store, new RuntimeDriver(), graphs)) {
            AgentRunResult result = new AgentRuntimeExecutionService(runtime, graphs).run(new AgentRunSpec()
                    .setInitialMessages(List.of(Map.of("role", "user", "content", "write")))
                    .setTools(tools).setModel("fake").setWorkspace(workspace).setSessionKey("cli:unknown")
                    .setApprovalService(approvals).setSideEffectStore(store.sideEffectStore()).setMaxIterations(3));

            assertEquals("approval_required", result.getStopReason());
            assertEquals(1, executions.get());
            ApprovalRequest request = approvals.listPending().get(0);
            assertEquals(SideEffectApplicationService.RETRY_ACTION, request.binding().actionType());
            assertEquals(result.getRunId(), request.binding().runId());
            assertEquals(SideEffectStatus.UNKNOWN,
                    store.sideEffectStore().load(request.binding().idempotencyKey()).orElseThrow().status());
        }
    }

    @Test
    void readOnlyToolUsesGraphOwnedThreeAttemptRetry(@TempDir Path workspace) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            public String getName() { return "flaky_read"; }
            public String getDescription() { return "flaky read"; }
            public ToolEffectPolicy effectPolicy() {
                return ToolEffectPolicy.readOnly(java.time.Duration.ofSeconds(30));
            }
            public Object execute(Map<String, Object> params) {
                if (executions.incrementAndGet() < 3) throw new IllegalStateException("temporary read failure");
                return "read-ok";
            }
        });
        AtomicInteger modelCalls = new AtomicInteger();
        LLMProvider provider = new LLMProvider("key", "local") {
            public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> definitions,
                                    String model, Integer maxTokens, Double temperature,
                                    String reasoningEffort, Object toolChoice) {
                if (modelCalls.incrementAndGet() == 1) return new LLMResponse().setFinishReason("tool_calls")
                        .setToolCalls(List.of(new ToolCallRequest("read-call", "flaky_read", Map.of())));
                return new LLMResponse("done").setFinishReason("stop");
            }
        };
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        ApprovalService approvals = new ApprovalService(store.approvalStore());
        AgentGraphFactory graphs = new AgentGraphFactory(provider, null, false, tools,
                store.sideEffectStore(), approvals);
        try (LocalAgentRuntime runtime = new LocalAgentRuntime(store, new RuntimeDriver(), graphs)) {
            AgentRunResult result = new AgentRuntimeExecutionService(runtime, graphs).run(new AgentRunSpec()
                    .setInitialMessages(List.of(Map.of("role", "user", "content", "read")))
                    .setTools(tools).setModel("fake").setWorkspace(workspace).setSessionKey("cli:read")
                    .setApprovalService(approvals).setSideEffectStore(store.sideEffectStore()).setMaxIterations(3));

            assertEquals("done", result.getFinalContent());
            assertEquals(3, executions.get());
            assertEquals(2, result.getRunEvents().stream()
                    .filter(event -> "node_retry_scheduled".equals(event.get("type"))).count());
        }
    }

    private static LLMProvider toolCallingProvider(String toolName) {
        return new LLMProvider("key", "local") {
            public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> definitions,
                                    String model, Integer maxTokens, Double temperature,
                                    String reasoningEffort, Object toolChoice) {
                return new LLMResponse().setFinishReason("tool_calls")
                        .setToolCalls(List.of(new ToolCallRequest("call", toolName, Map.of())));
            }
        };
    }
}
