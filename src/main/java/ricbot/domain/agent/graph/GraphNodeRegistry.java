package ricbot.domain.agent.graph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GraphNodeRegistry {
    private final Map<String, GraphNodeExecutor> executors = new ConcurrentHashMap<>();
    public GraphNodeRegistry register(String nodeId, GraphNodeExecutor executor) {
        if (nodeId == null || nodeId.isBlank() || executor == null) throw new IllegalArgumentException("node id and executor are required");
        executors.put(nodeId.trim(), executor); return this;
    }
    public GraphNodeExecutor require(String nodeId) {
        GraphNodeExecutor executor = executors.get(nodeId);
        if (executor == null) throw new IllegalStateException("graph node executor is not registered: " + nodeId);
        return executor;
    }
}
