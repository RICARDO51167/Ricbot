package ricbot.domain.eval;

import java.util.List;

public record EvalMatrixSpec(List<EvalModelTarget> targets, int repetitions, int longTrajectoryTurnThreshold) {
    public EvalMatrixSpec {
        targets = targets != null ? List.copyOf(targets) : List.of();
        if (targets.isEmpty()) throw new IllegalArgumentException("at least one model target is required");
        repetitions = Math.max(1, repetitions);
        longTrajectoryTurnThreshold = Math.max(2, longTrajectoryTurnThreshold);
    }
}
