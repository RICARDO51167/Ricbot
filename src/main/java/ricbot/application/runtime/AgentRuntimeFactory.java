package ricbot.application.runtime;

import ricbot.domain.agent.AgentInvocationRuntime;
import ricbot.domain.agent.AgentGraphFactory;
import ricbot.domain.agent.SideEffectStore;
import ricbot.domain.runtime.AgentRuntime;
import ricbot.domain.security.ApprovalService;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.infra.telemetry.RuntimeEventOpenTelemetrySubscriber;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.tool.api.ToolRegistry;

/** Production factory for the one Agent graph/runtime/driver composition. */
public final class AgentRuntimeFactory {
    private AgentRuntimeFactory() { }

    public static Components create(LLMProvider provider, SqliteRuntimeStore store, ToolRegistry tools,
                                    SideEffectStore sideEffects, ApprovalService approvals,
                                    OpenTelemetryRuntime telemetry, String model) {
        AgentGraphFactory graphFactory = new AgentGraphFactory(provider, null, false, tools, sideEffects, approvals);
        UnifiedAgentGraphFactory graphs = new UnifiedAgentGraphFactory(graphFactory);
        java.nio.file.Path workspace = store.database().getParent().getParent();
        String instanceId = ricbot.app.bootstrap.RuntimeStoreRegistry.lifecycle(workspace).instance().instanceId();
        RuntimeDriver driver = new RuntimeDriver(store, instanceId);
        LocalAgentRuntime runtime = new LocalAgentRuntime(store, driver, graphs);
        RuntimeEventOpenTelemetrySubscriber subscriber = new RuntimeEventOpenTelemetrySubscriber(
                telemetry.tracer("ricbot-runtime", ricbot.app.bootstrap.BuildVersion.current()));
        runtime.subscribe(subscriber);
        AgentInvocationRuntime invocations = new AgentRuntimeExecutionService(runtime, graphFactory);
        ricbot.domain.agent.AgentTeamWorkerRunner workers = new ricbot.domain.agent.AgentTeamWorkerRunner(
                workspace, invocations, model);
        TeamAgentGraphFactory teamGraphs = new TeamAgentGraphFactory(workspace, store, provider,
                model, approvals, workers);
        teamGraphs.runtime(runtime);
        graphs.team(teamGraphs);
        ricbot.app.bootstrap.RuntimeStoreRegistry.registerManaged(workspace, runtime);
        return new Components(runtime, invocations, subscriber);
    }

    public record Components(AgentRuntime runtime, AgentInvocationRuntime invocations,
                             RuntimeEventOpenTelemetrySubscriber telemetrySubscriber) { }
}
