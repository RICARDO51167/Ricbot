package ricbot.domain.runtime;

/** Public command surface for the single durable runtime. */
public interface DurableAgentRuntime extends AutoCloseable {
    RunView start(RunSpec spec);
    RunView submit(String runId, ExternalEvent event);
    RunView fork(ForkSpec spec);
    StateReplay replayState(String runId, long throughCommit);
    AutoCloseable subscribe(RuntimeEventSubscriber subscriber);
    default RuntimeHealth health() { return RuntimeHealth.unavailable(); }
    @Override void close();
}
