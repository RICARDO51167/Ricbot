package ricbot.domain.agent;

/** Invocation adapter used by interactive and child-task callers; scheduling remains in AgentRuntime. */
@FunctionalInterface
public interface AgentInvocationRuntime {
    AgentRunResult run(AgentRunSpec spec) throws Exception;
}
