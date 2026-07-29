package ricbot.domain.agent.eump;

/** Explicit executable nodes in the default ReAct graph. */
public enum AgentNodeType {
    INGEST,
    CONTEXT,
    COMPACT,
    MODEL,
    TOOLS,
    APPROVAL,
    STEERING,
    TERMINAL
}
