package ricbot.domain.agent.graph.interfacep;

import ricbot.domain.agent.graph.dto.GraphChannelWrite;

import java.util.List;

/** Pure channel reducer. Implementations must not mutate current or writes. */
@FunctionalInterface
public interface StateReducer {
    Object reduce(Object current, List<GraphChannelWrite> writes);
}
