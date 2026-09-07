package ricbot.domain.runtime;

/** Executes one fixed phase and returns facts; it never owns or mutates RunState. */
@FunctionalInterface
public interface PhaseExecutor {
    PhaseResult execute(PhaseContext context);
}
