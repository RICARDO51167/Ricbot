package ricbot.infra.execution;

public interface ExecutionBackend {
    String name();
    ExecutionCapabilities probe();
    ExecutionResult execute(ExecutionRequest request) throws Exception;
}
