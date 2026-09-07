package ricbot.application.runtime;

import ricbot.domain.runtime.PhaseContext;
import ricbot.domain.runtime.PhaseResult;

/** One fixed durable-runtime phase boundary. */
interface AgentPhaseHandler {
    PhaseResult execute(PhaseContext context);
}
