package ricbot.domain.change;

import ricbot.domain.runtime.DurableAgentRuntime;
import ricbot.domain.runtime.RunSpec;
import ricbot.domain.runtime.RunView;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Starts approval-gated ChangeSet operations as ordinary durable v6 Runs. */
public final class ChangeActionRuntimeService {
    public static final String EXECUTION_POLICY = "changeset-action-v1";

    private final Path workspace;
    private final DurableAgentRuntime runtime;

    public ChangeActionRuntimeService(Path workspace, DurableAgentRuntime runtime) {
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public Result start(PendingChangeAction action) {
        Objects.requireNonNull(action, "action");
        String runId = "change-run-" + UUID.randomUUID();
        RunSpec spec = new RunSpec(runId, "", runId, "", List.of(),
                "Apply approved ChangeSet " + action.actionType().name().toLowerCase(java.util.Locale.ROOT),
                EXECUTION_POLICY, 16, Map.of(
                        "runtimeOperation", "change-action",
                        "workspace", workspace.toString(),
                        "changeAction", action));
        RunView view = runtime.start(spec);
        String requestId = String.valueOf(view.state().channels().getOrDefault("approvalRequestId", ""));
        if (requestId.isBlank()) throw new IllegalStateException("change action Run did not create an approval request");
        return new Result(runId, requestId, view);
    }

    public record Result(String runId, String requestId, RunView view) { }
}
