package ricbot.domain.agent.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Declares every writable graph channel and its deterministic merge rule. */
public final class GraphStateSchema {
    private final Map<String, StateReducer> reducers;
    private final boolean dynamicReplaceChannels;

    private GraphStateSchema(Map<String, StateReducer> reducers, boolean dynamicReplaceChannels) {
        this.reducers = Collections.unmodifiableMap(new LinkedHashMap<>(reducers));
        this.dynamicReplaceChannels = dynamicReplaceChannels;
    }

    public Object reduce(String channel, Object current, List<GraphChannelWrite> writes) {
        StateReducer reducer = reducers.get(channel);
        if (reducer == null && dynamicReplaceChannels) reducer = StateReducers.replace();
        if (reducer == null) throw new StateReducers.GraphReductionException("unknown graph channel: " + channel);
        return reducer.reduce(current, List.copyOf(writes));
    }

    public boolean contains(String channel) { return reducers.containsKey(channel); }
    public Map<String, StateReducer> reducers() { return reducers; }

    public static Builder builder() { return new Builder(); }
    static GraphStateSchema legacyDynamic() { return new GraphStateSchema(Map.of(), true); }

    public static final class Builder {
        private final Map<String, StateReducer> reducers = new LinkedHashMap<>();
        public Builder channel(String name, StateReducer reducer) {
            String clean = name != null ? name.trim() : "";
            if (clean.isBlank() || reducer == null) throw new IllegalArgumentException("channel name and reducer are required");
            if (reducers.putIfAbsent(clean, reducer) != null) throw new IllegalArgumentException("duplicate channel: " + clean);
            return this;
        }
        public GraphStateSchema build() { return new GraphStateSchema(reducers, false); }
    }
}
