package ricbot.domain.runtime;

import java.util.List;

public record PhaseContext(RunState state, String activationId, List<ExternalEvent> inbox) {
    public PhaseContext { inbox = List.copyOf(inbox != null ? inbox : List.of()); }
}
