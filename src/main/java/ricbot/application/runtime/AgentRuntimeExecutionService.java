package ricbot.application.runtime;

import ricbot.domain.agent.AgentInvocationRuntime;
import ricbot.domain.agent.AgentRunResult;
import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.AgentGraphFactory;
import ricbot.domain.runtime.AgentRuntime;
import ricbot.domain.runtime.RunRequest;
import ricbot.domain.runtime.RunView;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Adapts an interactive invocation to the single production AgentRuntime API. */
public final class AgentRuntimeExecutionService implements AgentInvocationRuntime {
    private final AgentRuntime runtime;
    private final AgentGraphFactory graphs;

    public AgentRuntimeExecutionService(AgentRuntime runtime, AgentGraphFactory graphs) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.graphs = java.util.Objects.requireNonNull(graphs, "graphs");
    }

    @Override public AgentRunResult run(AgentRunSpec spec) throws Exception {
        String runId = spec.getRunId() != null && !spec.getRunId().isBlank()
                ? spec.getRunId().trim() : UUID.randomUUID().toString();
        Instant started = Instant.now();
        graphs.prepare(runId, spec);
        try {
            RunView view = runtime.start(new RunRequest(runId, clean(spec.getSessionKey()), RunRequest.Mode.AGENT,
                    goal(spec), runtimeWorkspace(spec), Math.max(12, spec.getMaxIterations() * 3), metadata(spec)));
            return graphs.result(runId, started, view.state());
        } catch (Exception failure) {
            return graphs.failedResult(runId, started, failure);
        } finally {
            graphs.release(runId);
        }
    }

    private static Map<String, Object> metadata(AgentRunSpec spec) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", clean(spec.getModel()));
        metadata.put("maxIterations", spec.getMaxIterations());
        metadata.put("maxToolResultChars", spec.getMaxToolResultChars());
        metadata.put("contextWindowTokens", spec.getContextWindowTokens() != null
                ? spec.getContextWindowTokens() : 128_000);
        metadata.put("providerRetryMode", clean(spec.getProviderRetryMode()));
        metadata.put("allowedTools", spec.getAllowedTools() != null ? spec.getAllowedTools() : java.util.List.of());
        metadata.put("toolWorkspace", spec.getWorkspace() != null ? spec.getWorkspace().toString() : "");
        if (spec.getMetadata() != null) metadata.putAll(spec.getMetadata());
        return Map.copyOf(metadata);
    }

    private static String goal(AgentRunSpec spec) {
        if (spec.getInitialMessages() != null) {
            for (int index = spec.getInitialMessages().size() - 1; index >= 0; index--) {
                Map<String, Object> message = spec.getInitialMessages().get(index);
                if ("user".equals(String.valueOf(message.get("role")))) {
                    String content = clean(String.valueOf(message.getOrDefault("content", "")));
                    if (!content.isBlank()) return content;
                }
            }
        }
        return "agent invocation";
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static java.nio.file.Path runtimeWorkspace(AgentRunSpec spec) {
        return spec.getRuntimeWorkspace() != null ? spec.getRuntimeWorkspace() : spec.getWorkspace();
    }
}
