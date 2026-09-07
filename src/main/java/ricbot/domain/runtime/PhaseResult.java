package ricbot.domain.runtime;

import java.util.List;

public record PhaseResult(List<ChannelWrite> writes, List<RuntimeCommand> commands) {
    public PhaseResult {
        writes = List.copyOf(writes != null ? writes : List.of());
        commands = List.copyOf(commands != null ? commands : List.of());
    }
    public static PhaseResult route(RuntimePhase phase) {
        return new PhaseResult(List.of(), List.of(new RuntimeCommand.Transition(phase)));
    }
}
