package ricbot.domain.runtime;

import java.util.List;

public record StateReplay(String runId, long throughCommit, RunState state,
                          List<CommittedWrite> writes, String projectionDigest) {
    public StateReplay { writes = List.copyOf(writes != null ? writes : List.of()); }
}
