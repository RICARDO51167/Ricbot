package ricbot.infra.execution;

import java.util.Objects;
import java.util.function.Supplier;

/** Transport-neutral remote backend adapter for E2B/OpenSandbox/Kubernetes clients. */
public final class RemoteExecutionBackend implements ExecutionBackend {
    private final String provider;
    private final RemoteExecutionClient client;
    private final Supplier<ExecutionCapabilities> probe;

    public RemoteExecutionBackend(
            String provider,
            RemoteExecutionClient client,
            Supplier<ExecutionCapabilities> probe
    ) {
        this.provider = provider != null && !provider.isBlank() ? provider.trim() : "remote";
        this.client = Objects.requireNonNull(client, "client");
        this.probe = probe != null ? probe : () ->
                new ExecutionCapabilities(true, true, true, true, this.provider);
    }

    public String name() { return "remote:" + provider; }
    public ExecutionCapabilities probe() { return probe.get(); }

    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        ExecutionCapabilities capabilities = probe();
        if (!capabilities.available()) throw new IllegalStateException(name() + " is unavailable: " + capabilities.detail());
        ExecutionResult result = client.execute(request);
        return new ExecutionResult(result.exitCode(), result.stdout(), result.stderr(), result.timedOut(),
                result.truncated(), result.duration(), name(), result.metadata());
    }
}
