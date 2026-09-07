package ricbot.domain.runtime;

import java.util.List;

public record CommittedWrite(long commitSequence, long superstep, RuntimePhase phase,
                             List<ChannelWrite> writes, List<RuntimeCommand> commands) {
    public CommittedWrite {
        writes = List.copyOf(writes != null ? writes : List.of());
        commands = List.copyOf(commands != null ? commands : List.of());
    }
}
