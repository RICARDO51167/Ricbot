package ricbot.application.runtime;

import ricbot.domain.agent.interfacep.AgentInvocationRuntime;
import ricbot.domain.runtime.DurableAgentRuntime;
import ricbot.domain.security.ApprovalService;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.infra.telemetry.RuntimeEventOpenTelemetrySubscriber;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.tool.api.ToolRegistry;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.infra.config.Config;
import ricbot.domain.config.ModelCard;

/** Production composition root for the single durable v6 runtime. */
public final class AgentRuntimeFactory {
    private AgentRuntimeFactory() { }

    public static Components create(LLMProvider provider, SqliteRuntimeStore store, ToolRegistry tools,
                                    ApprovalService approvals,
                                    OpenTelemetryRuntime telemetry, String model) {
        return create(provider, store, tools, approvals, telemetry, model,
                BudgetPolicy.unlimited(), new Config.ContextOffloadConfig(), "UTC");
    }

    public static Components create(LLMProvider provider, SqliteRuntimeStore store, ToolRegistry tools,
                                    ApprovalService approvals,
                                    OpenTelemetryRuntime telemetry, String model, BudgetPolicy budgetPolicy,
                                    Config.ContextOffloadConfig offload, String timezone) {
        return create(provider, store, tools, approvals, telemetry, model, budgetPolicy,
                offload, timezone, null);
    }

    public static Components create(LLMProvider provider, SqliteRuntimeStore store, ToolRegistry tools,
                                    ApprovalService approvals,
                                    OpenTelemetryRuntime telemetry, String model, BudgetPolicy budgetPolicy,
                                    Config.ContextOffloadConfig offload, String timezone,
                                    ModelCard.Pricing pricing) {
        java.nio.file.Path workspace = store.database().getParent().getParent();
        var durableStore = ricbot.app.bootstrap.RuntimeStoreRegistry.durable(workspace);
        String owner = "runtime-" + java.util.UUID.randomUUID();
        AgentPhaseExecutor phases = new AgentPhaseExecutor(durableStore, store, provider, tools, approvals,
                model, workspace, java.time.Clock.systemUTC(), owner);
        LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(durableStore,
                phases, store, java.time.Clock.systemUTC(), owner,
                java.time.Duration.ofSeconds(30));
        
        // 设置遥测订阅者
        RuntimeEventOpenTelemetrySubscriber subscriber = new RuntimeEventOpenTelemetrySubscriber(
                telemetry.tracer("ricbot-runtime", ricbot.app.bootstrap.BuildVersion.current()));
        runtime.subscribe(subscriber);
        
        // 创建调用运行时服务
        AgentInvocationRuntime invocations = new AgentRuntimeExecutionService(runtime, store, phases,
                budgetPolicy, offload, timezone, pricing);
        
        // 注册受管运行时
        ricbot.app.bootstrap.RuntimeStoreRegistry.registerManaged(workspace, runtime);
        
        return new Components(runtime, invocations, subscriber);
    }

    public record Components(DurableAgentRuntime runtime, AgentInvocationRuntime invocations,
                             RuntimeEventOpenTelemetrySubscriber telemetrySubscriber) { }
}
