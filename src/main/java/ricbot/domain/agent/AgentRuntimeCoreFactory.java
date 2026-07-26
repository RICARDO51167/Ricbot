package ricbot.domain.agent;

import ricbot.domain.agent.context.StructuredContextService;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.session.SessionManager;
import ricbot.domain.trace.TraceStore;
import ricbot.infra.config.Config;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.tool.api.ToolRegistry;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.runtime.SqliteSessionManager;
import ricbot.app.bootstrap.RuntimeStoreRegistry;

import java.nio.file.Path;
import java.util.List;
import ricbot.application.runtime.AgentRuntimeFactory;

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
        SqliteRuntimeStore runtimeStore = RuntimeStoreRegistry.acquire(workspace);
        SessionManager runtimeSessions = suppliedSessions != null ? suppliedSessions
                : new SqliteSessionManager(workspace, runtimeStore);
        AgentPersistenceComponents persistence = AgentPersistenceFactory.create(runtimeStore, runtimeSessions);
        OpenTelemetryRuntime telemetry = OpenTelemetryRuntime.fromEnvironment();
        TraceStore traces = new TraceStore(runtimeStore);
        SideEffectStore sideEffects = new AuditedSideEffectStore(runtimeStore.sideEffectStore(), traces);
        MemoryStore memory = new MemoryStore(workspace);
        StructuredContextService compactor = new StructuredContextService(provider, model,
                persistence.sessionManager(), contextWindowTokens, 4096);
        ApprovalService approvals = new ApprovalService(runtimeStore.approvalStore(), traces);
        SideEffectApplicationService sideEffectApplication = new SideEffectApplicationService(sideEffects, approvals);
        ToolRegistry tools = new ToolRegistry();
        AgentRuntimeFactory.Components runtime = AgentRuntimeFactory.create(provider, runtimeStore, tools,
                sideEffects, approvals, telemetry, model);
        return new AgentRuntimeCore(contextBuilder, persistence, telemetry, traces, sideEffects,
                memory, compactor, approvals, sideEffectApplication,
                tools, runtime.runtime(), runtime.invocations());
    }
}
