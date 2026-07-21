package ricbot.domain.agent;

import ricbot.domain.memory.Consolidator;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.session.SessionManager;
import ricbot.domain.trace.TraceStore;
import ricbot.infra.config.Config;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

/** Pure bootstrap factory for the stateful core shared by AgentLoop services. */
public final class AgentRuntimeCoreFactory {
    private AgentRuntimeCoreFactory() {
    }

    public static AgentRuntimeCore create(LLMProvider provider, Path rawWorkspace, String model,
                                          int contextWindowTokens, int maxToolResultChars,
                                          Config.ExecToolConfig execConfig,
                                          boolean restrictToWorkspace, SessionManager suppliedSessions,
                                          String timezone, int sessionTtlMinutes) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        ContextBuilder contextBuilder = new ContextBuilder(workspace, timezone);
        AgentPersistenceComponents persistence = AgentPersistenceFactory.create(workspace, suppliedSessions);
        OpenTelemetryRuntime telemetry = OpenTelemetryRuntime.fromEnvironment();
        RunEventSink events = RunEventSink.durableWithDiagnostics(persistence.journalStore(),
                new OpenTelemetryRunEventSink(telemetry.tracer("ricbot.agent", "1.0")));
        TraceStore traces = new TraceStore(workspace);
        SideEffectStore sideEffects = new AuditedSideEffectStore(persistence.sideEffectStore(), traces);
        MemoryStore memory = new MemoryStore(workspace);
        Consolidator compactor = new Consolidator(memory, provider, model, persistence.sessionManager(),
                contextWindowTokens, 4096);
        ApprovalService approvals = new ApprovalService(traces);
        SideEffectApplicationService sideEffectApplication = new SideEffectApplicationService(sideEffects, approvals);
        AutoCompact autoCompact = new AutoCompact(persistence.sessionManager(), compactor, sessionTtlMinutes);
        SpawnWorkerService workers = new SpawnWorkerService(provider, workspace, maxToolResultChars, model,
                execConfig, restrictToWorkspace);
        return new AgentRuntimeCore(contextBuilder, persistence, telemetry, events, traces, sideEffects,
                memory, compactor, approvals, sideEffectApplication, autoCompact, workers,
                new ToolRegistry(), new GraphRunService(provider));
    }
}
