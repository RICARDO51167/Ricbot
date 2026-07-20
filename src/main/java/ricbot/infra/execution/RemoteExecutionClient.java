package ricbot.infra.execution;

@FunctionalInterface
public interface RemoteExecutionClient {
    ExecutionResult execute(ExecutionRequest request) throws Exception;
}
