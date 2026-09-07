package ricbot.domain.agent;

import ricbot.domain.runtime.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only application facade over the schema-v3 run projections. */
public final class RuntimeQueryService implements RunQuery {
    private final RunQuery query;
    private final DurableAgentRuntime runtime;

    public RuntimeQueryService(Path workspace, DurableAgentRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.query = runtime instanceof RunQuery value ? value
                : ricbot.app.bootstrap.RuntimeStoreRegistry.durable(workspace.toAbsolutePath().normalize());
    }

    @Override public List<RunView> list() { return query.list(); }
    @Override public java.util.Optional<RunView> get(String runId) { return query.get(runId); }
    @Override public List<RunView> children(String runId) { return query.children(runId); }
    @Override public List<RuntimeEvent> events(String runId) { return query.events(runId); }
    @Override public List<TimelineEvent> timeline(String runId) { return query.timeline(runId); }
    public StateReplay replay(String runId, long sequence) { return runtime.replayState(runId, sequence); }

    public Map<String, Object> report(String runId) {
        RunView view = get(runId).orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        RunState state = view.state();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("graphVersion", state.graphVersion());
        report.put("status", state.status());
        report.put("phase", state.phase());
        report.put("superstep", state.superstep());
        report.put("commitSequence", state.commitSequence());
        report.put("waitReason", state.waitReason());
        report.put("parentRunId", state.spec().parentRunId());
        report.put("rootRunId", state.spec().rootRunId());
        report.put("retryOfRunId", state.spec().retryOfRunId());
        report.put("dependencies", state.spec().dependencies());
        report.put("children", children(runId).stream().map(child -> child.state().spec().runId()).toList());
        report.put("artifacts", state.artifactReferences());
        report.put("projectionDigest", view.projectionDigest());
        return java.util.Collections.unmodifiableMap(report);
    }
}
