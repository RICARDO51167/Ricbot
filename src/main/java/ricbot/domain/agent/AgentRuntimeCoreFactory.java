package ricbot.domain.agent;

import ricbot.domain.agent.dto.AgentPersistenceComponents;
import ricbot.domain.agent.dto.AgentRuntimeCore;
import ricbot.domain.agent.interfacep.SideEffectStore;
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

import ricbot.application.runtime.AgentRuntimeFactory;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.domain.config.ModelCard;

/** Pure bootstrap factory for the stateful core shared by AgentLoop services. */
public final class AgentRuntimeCoreFactory {
    private AgentRuntimeCoreFactory() {
    }

    public static AgentRuntimeCore create(LLMProvider provider, Path rawWorkspace, String model,
                                          int contextWindowTokens, int maxToolResultChars,
                                          Config.ExecToolConfig execConfig,
                                          boolean restrictToWorkspace, SessionManager suppliedSessions,
                                          String timezone, int sessionTtlMinutes) {
        return create(provider, rawWorkspace, model, contextWindowTokens, maxToolResultChars, execConfig,
                restrictToWorkspace, suppliedSessions, timezone, sessionTtlMinutes,
                BudgetPolicy.unlimited(), new Config.ContextOffloadConfig());
    }

    public static AgentRuntimeCore create(LLMProvider provider, Path rawWorkspace, String model,
                                          int contextWindowTokens, int maxToolResultChars,
                                          Config.ExecToolConfig execConfig, boolean restrictToWorkspace,
                                          SessionManager suppliedSessions, String timezone, int sessionTtlMinutes,
                                          BudgetPolicy budgetPolicy, Config.ContextOffloadConfig offload) {
        return create(provider, rawWorkspace, model, contextWindowTokens, maxToolResultChars, execConfig,
                restrictToWorkspace, suppliedSessions, timezone, sessionTtlMinutes, budgetPolicy, offload, null);
    }

    public static AgentRuntimeCore create(LLMProvider provider, Path rawWorkspace, String model,
                                          int contextWindowTokens, int maxToolResultChars,
                                          Config.ExecToolConfig execConfig, boolean restrictToWorkspace,
                                          SessionManager suppliedSessions, String timezone, int sessionTtlMinutes,
                                          BudgetPolicy budgetPolicy, Config.ContextOffloadConfig offload,
                                          ModelCard.Pricing pricing) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        ContextBuilder contextBuilder = new ContextBuilder(workspace, timezone);
        SqliteRuntimeStore runtimeStore = RuntimeStoreRegistry.acquire(workspace);
        SessionManager runtimeSessions = suppliedSessions != null ? suppliedSessions
                : new SqliteSessionManager(runtimeStore);
        AgentPersistenceComponents persistence = AgentPersistenceFactory.create(runtimeStore, runtimeSessions);
        OpenTelemetryRuntime telemetry = OpenTelemetryRuntime.fromEnvironment();
        TraceStore traces = new TraceStore(runtimeStore);
        SideEffectStore sideEffects = new AuditedSideEffectStore(runtimeStore.sideEffectStore(), traces);
        MemoryStore memory = new MemoryStore(workspace);
        ApprovalService approvals = new ApprovalService(runtimeStore.approvalStore(), traces);
        SideEffectApplicationService sideEffectApplication = new SideEffectApplicationService(sideEffects, approvals);
        ToolRegistry tools = new ToolRegistry();
        AgentRuntimeFactory.Components runtime = AgentRuntimeFactory.create(provider, runtimeStore, tools,
                sideEffects, approvals, telemetry, model, budgetPolicy, offload, timezone, pricing);
        return new AgentRuntimeCore(contextBuilder, persistence, telemetry, traces, sideEffects,
                memory, approvals, sideEffectApplication,
                tools, runtime.runtime(), runtime.invocations());
    }
}
