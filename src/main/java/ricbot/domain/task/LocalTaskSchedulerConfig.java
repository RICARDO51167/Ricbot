package ricbot.domain.task;

public record LocalTaskSchedulerConfig(int maxParallel, int queueCapacity, int maxTasksPerPlan,
                                       int maxDelegationDepth) {
    public LocalTaskSchedulerConfig {
        if (maxParallel < 1 || queueCapacity < 1 || maxTasksPerPlan < 1 || maxDelegationDepth < 0) {
            throw new IllegalArgumentException("invalid local task scheduler limits");
        }
    }
    public static LocalTaskSchedulerConfig defaults() { return new LocalTaskSchedulerConfig(4, 256, 8, 2); }
}
