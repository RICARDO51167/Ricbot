package ricbot.domain.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Everything made visible by one successful superstep transaction. */
public record CommitBatch(Activation activation, RunState expectedState, Reduction reduction,
                          List<ChannelWrite> writes, List<RuntimeCommand> commands,
                          List<String> consumedEventIds, String timelineType,
                          Map<String, Object> timelineDetail, Instant committedAt) {
    public CommitBatch {
        if (activation == null || expectedState == null || reduction == null || committedAt == null) {
            throw new IllegalArgumentException("activation, states and committedAt are required");
        }
        writes = List.copyOf(writes != null ? writes : List.of());
        commands = List.copyOf(commands != null ? commands : List.of());
        consumedEventIds = List.copyOf(consumedEventIds != null ? consumedEventIds : List.of());
        timelineType = timelineType != null ? timelineType.trim() : "";
        timelineDetail = Map.copyOf(timelineDetail != null ? timelineDetail : Map.of());
    }
}
