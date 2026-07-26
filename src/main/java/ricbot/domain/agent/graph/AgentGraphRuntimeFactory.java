package ricbot.domain.agent.graph;

import ricbot.domain.agent.AgentNodeType;

/** Versioned graph-id factory shared by fresh Agent runs and process-safe resume paths. */
public final class AgentGraphRuntimeFactory {
    public static final String AGENT_GRAPH_ID = "ricbot-agent-runtime-v3";
    private AgentGraphRuntimeFactory() { }

    public static AgentGraphDefinition definition(int maxSupersteps) {
        return AgentGraphDefinition.builder(AGENT_GRAPH_ID, AgentNodeType.INGEST.name())
                .node(AgentNodeType.INGEST.name())
                .node(AgentNodeType.CONTEXT.name(), AgentNodeType.CONTEXT.name(), java.time.Duration.ofMinutes(1),
                        GraphRetryPolicy.readOnly(3, java.time.Duration.ofMillis(250)), GraphFailurePolicy.FAIL_STOP)
                .node(AgentNodeType.COMPACT.name(), AgentNodeType.COMPACT.name(), java.time.Duration.ofMinutes(3),
                        GraphRetryPolicy.readOnly(2, java.time.Duration.ofSeconds(1)), GraphFailurePolicy.FAIL_STOP)
                .node(AgentNodeType.MODEL.name(), AgentNodeType.MODEL.name(), java.time.Duration.ofMinutes(5),
                        GraphRetryPolicy.readOnly(3, java.time.Duration.ofMillis(250)), GraphFailurePolicy.FAIL_STOP)
                .node(AgentNodeType.TOOLS.name(), AgentNodeType.TOOLS.name(), java.time.Duration.ofMinutes(10),
                        GraphRetryPolicy.sideEffecting(), GraphFailurePolicy.FAIL_STOP)
                .node(AgentNodeType.APPROVAL.name()).node(AgentNodeType.STEERING.name())
                .terminalNode(AgentNodeType.TERMINAL.name())
                .edge(AgentNodeType.INGEST.name(), "next", AgentNodeType.CONTEXT.name())
                .edge(AgentNodeType.CONTEXT.name(), "compact", AgentNodeType.COMPACT.name())
                .edge(AgentNodeType.CONTEXT.name(), "model", AgentNodeType.MODEL.name())
                .edge(AgentNodeType.COMPACT.name(), "next", AgentNodeType.MODEL.name())
                .edge(AgentNodeType.MODEL.name(), "tools", AgentNodeType.TOOLS.name())
                .edge(AgentNodeType.MODEL.name(), "overflow", AgentNodeType.COMPACT.name())
                .edge(AgentNodeType.MODEL.name(), "terminal", AgentNodeType.TERMINAL.name())
                .edge(AgentNodeType.TOOLS.name(), "next", AgentNodeType.STEERING.name())
                .edge(AgentNodeType.TOOLS.name(), "approval", AgentNodeType.APPROVAL.name())
                .edge(AgentNodeType.APPROVAL.name(), "approved", AgentNodeType.TOOLS.name())
                .edge(AgentNodeType.APPROVAL.name(), "rejected", AgentNodeType.STEERING.name())
                .edge(AgentNodeType.STEERING.name(), "next", AgentNodeType.CONTEXT.name())
                .edge(AgentNodeType.STEERING.name(), "terminal", AgentNodeType.TERMINAL.name())
                .maxSupersteps(Math.max(12, maxSupersteps * 3)).build();
    }

    public static GraphStateSchema schema() {
        return GraphStateSchema.builder().channel("messages", StateReducers.replace())
                .channel("modelResponse", StateReducers.replace())
                .channel("pendingToolCalls", StateReducers.replace())
                .channel("toolBatch", StateReducers.replace())
                .channel("approvalRequestIds", StateReducers.replace())
                .channel("approvalSignal", StateReducers.replace())
                .channel("compactRequested", StateReducers.replace())
                .channel("contextUtilization", StateReducers.replace())
                .channel("contextCompactedAt", StateReducers.replace())
                .channel("stopReason", StateReducers.replace())
                .channel("iterations", StateReducers.replace())
                .channel("usage", StateReducers.replace())
                .channel("finalContent", StateReducers.replace())
                .channel("error", StateReducers.replace()).build();
    }
}
