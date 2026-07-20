package ricbot.domain.agent.graph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GraphConditionRegistry {
    private final Map<String, GraphCondition> conditions = new ConcurrentHashMap<>();

    public GraphConditionRegistry() { register("always", (state, facts) -> true); }
    public GraphConditionRegistry register(String id, GraphCondition condition) {
        if (id == null || id.isBlank() || condition == null) throw new IllegalArgumentException("condition id and value are required");
        conditions.put(id.trim(), condition);
        return this;
    }
    public GraphCondition require(String id) {
        GraphCondition condition = conditions.get(id);
        if (condition == null) throw new IllegalStateException("graph condition is not registered: " + id);
        return condition;
    }
}
