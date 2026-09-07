package ricbot.application.runtime;

import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.event.AgentEvent;
import ricbot.domain.runtime.CancellationPort;
import ricbot.domain.runtime.CrashInjector;
import ricbot.domain.runtime.DurableRuntimeStore;
import ricbot.domain.runtime.PhaseContext;
import ricbot.domain.runtime.PhaseExecutor;
import ricbot.domain.runtime.PhaseResult;
import ricbot.domain.runtime.RuntimePhase;
import ricbot.domain.runtime.TranscriptPort;
import ricbot.domain.security.ApprovalService;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed v6 phase router. All phase behavior lives in focused handlers. */
public final class AgentPhaseExecutor implements PhaseExecutor, CancellationPort {
    private final AgentPhaseSupport services;
    private final Map<RuntimePhase, AgentPhaseHandler> handlers;
    private final AgentPhaseHandler changeActions;

    public AgentPhaseExecutor(DurableRuntimeStore store, TranscriptPort transcripts,
                              LLMProvider provider, ToolRegistry tools,
                              ApprovalService approvals, String defaultModel, Path workspace,
                              Clock clock, String owner) {
        this(store, transcripts, provider, tools, approvals, defaultModel, workspace, clock, owner,
                CrashInjector.NONE);
    }

    public AgentPhaseExecutor(DurableRuntimeStore store, TranscriptPort transcripts,
                              LLMProvider provider, ToolRegistry tools,
                              ApprovalService approvals, String defaultModel, Path workspace,
                              Clock clock, String owner, CrashInjector crashes) {
        services = new AgentPhaseSupport(store, transcripts, provider, tools, approvals, defaultModel,
                workspace, clock, owner, crashes);
        EnumMap<RuntimePhase, AgentPhaseHandler> routes = new EnumMap<>(RuntimePhase.class);
        routes.put(RuntimePhase.INGEST, new IngestPhaseHandler(services));
        AgentPhaseHandler context = new ContextCompactPhaseHandler(services);
        routes.put(RuntimePhase.CONTEXT, context);
        routes.put(RuntimePhase.COMPACT, context);
        routes.put(RuntimePhase.MODEL, new ModelPhaseHandler(services));
        routes.put(RuntimePhase.TOOLS, new ToolEffectPhaseHandler(services));
        routes.put(RuntimePhase.DELEGATE, new DelegatePhaseHandler(services));
        handlers = Map.copyOf(routes);
        changeActions = new ChangeActionPhaseHandler(services);
    }

    public void attach(String runId, AgentRunSpec spec) { services.attach(runId, spec); }

    public InvocationObservation observation(String runId) {
        RunExecutionRegistry.InvocationObservation value = services.observation(runId);
        return new InvocationObservation(value.toolsUsed(), value.toolEvents(), value.events());
    }

    public void detach(String runId) { services.detach(runId); }

    @Override public void cancel(String runId) { services.cancel(runId); }

    @Override public PhaseResult execute(PhaseContext context) {
        Objects.requireNonNull(context, "context");
        RuntimePhase phase = context.state().phase();
        if (phase == RuntimePhase.WAIT || phase == RuntimePhase.TERMINAL) {
            throw new IllegalStateException("non-executable phase: " + phase);
        }
        if (AgentPhaseSupport.changeActionRun(context.state())) return changeActions.execute(context);
        AgentPhaseHandler handler = handlers.get(phase);
        if (handler == null) throw new IllegalStateException("phase handler is not registered: " + phase);
        return handler.execute(context);
    }

    public record InvocationObservation(List<String> toolsUsed, List<Map<String, Object>> toolEvents,
                                        List<AgentEvent> events) {
        public InvocationObservation {
            toolsUsed = List.copyOf(toolsUsed != null ? toolsUsed : List.of());
            toolEvents = List.copyOf(toolEvents != null ? toolEvents : List.of());
            events = List.copyOf(events != null ? events : List.of());
        }
        public static InvocationObservation empty() {
            return new InvocationObservation(List.of(), List.of(), List.of());
        }
    }
}
