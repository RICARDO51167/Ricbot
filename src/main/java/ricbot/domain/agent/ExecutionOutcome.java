package ricbot.domain.agent;

final class ExecutionOutcome {

    private final AgentRunResult runResult;
    private final String finalContent;

    ExecutionOutcome(AgentRunResult runResult, String finalContent) {
        this.runResult = runResult;
        this.finalContent = finalContent;
    }

    AgentRunResult runResult() {
        return runResult;
    }

    String finalContent() {
        return finalContent;
    }
}
