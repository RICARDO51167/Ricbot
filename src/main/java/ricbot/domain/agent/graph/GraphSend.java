package ricbot.domain.agent.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A dynamic activation emitted by a node. */
public record GraphSend(String nodeId, String key, Map<String, Object> input) {
    public GraphSend {
        nodeId = required(nodeId, "nodeId");
        key = key != null ? key.trim() : "";
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input != null ? input : Map.of()));
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
