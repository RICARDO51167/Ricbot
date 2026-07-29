package ricbot.domain.agent.dto;

import ricbot.domain.agent.AgentRunResult;

public record ExecutionOutcome(AgentRunResult runResult, String finalContent) {

}
