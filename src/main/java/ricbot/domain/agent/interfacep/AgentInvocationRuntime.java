package ricbot.domain.agent.interfacep;

import ricbot.domain.agent.AgentRunResult;
import ricbot.domain.agent.AgentRunSpec;

/** Invocation adapter used by interactive and child-task callers; scheduling remains in AgentRuntime. */
@FunctionalInterface
public interface AgentInvocationRuntime {
    AgentRunResult run(AgentRunSpec spec) throws Exception;
}
