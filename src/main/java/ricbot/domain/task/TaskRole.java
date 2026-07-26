package ricbot.domain.task;

/** Runtime scheduling role. It is policy metadata, not a separate orchestration layer. */
public enum TaskRole {
    LEADER,
    PLANNER,
    EXPLORER,
    DEVELOPER,
    TESTER,
    REVIEWER,
    VERIFIER,
    SYNTHESIZER
}
