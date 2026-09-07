package ricbot.domain.runtime;

import java.util.List;

public record Reduction(RunState state, List<RunSpec> childRuns) {
    public Reduction { childRuns = List.copyOf(childRuns != null ? childRuns : List.of()); }
}
