package ricbot.application.runtime;

import ricbot.domain.agent.interfacep.AgentInvocationRuntime;
import ricbot.domain.agent.AgentGraphFactory;
import ricbot.domain.agent.interfacep.SideEffectStore;
import ricbot.domain.runtime.AgentRuntime;
import ricbot.domain.security.ApprovalService;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.infra.telemetry.RuntimeEventOpenTelemetrySubscriber;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.tool.api.ToolRegistry;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.infra.config.Config;
import ricbot.domain.config.ModelCard;

/** 生产工厂，用于构建单一的 Agent 图/运行时/驱动组合。 */
public final class AgentRuntimeFactory {
    private AgentRuntimeFactory() { }

    public static Components create(LLMProvider provider, SqliteRuntimeStore store, ToolRegistry tools,
                                    SideEffectStore sideEffects, ApprovalService approvals,
                                    OpenTelemetryRuntime telemetry, String model) {
        return create(provider, store, tools, sideEffects, approvals, telemetry, model,
                BudgetPolicy.unlimited(), new Config.ContextOffloadConfig(), "UTC");
    }

    public static Components create(LLMProvider provider, SqliteRuntimeStore store, ToolRegistry tools,
                                    SideEffectStore sideEffects, ApprovalService approvals,
                                    OpenTelemetryRuntime telemetry, String model, BudgetPolicy budgetPolicy,
                                    Config.ContextOffloadConfig offload, String timezone) {
        return create(provider, store, tools, sideEffects, approvals, telemetry, model, budgetPolicy,
                offload, timezone, null);
    }

    public static Components create(LLMProvider provider, SqliteRuntimeStore store, ToolRegistry tools,
                                    SideEffectStore sideEffects, ApprovalService approvals,
                                    OpenTelemetryRuntime telemetry, String model, BudgetPolicy budgetPolicy,
                                    Config.ContextOffloadConfig offload, String timezone,
                                    ModelCard.Pricing pricing) {
        // 创建基础 Agent 图工厂
        AgentGraphFactory graphFactory = new AgentGraphFactory(provider, null, false, tools, sideEffects, approvals);
        UnifiedAgentGraphFactory graphs = new UnifiedAgentGraphFactory(graphFactory);
        
        // 获取工作区路径和实例ID
        java.nio.file.Path workspace = store.database().getParent().getParent();
        String instanceId = ricbot.app.bootstrap.RuntimeStoreRegistry.lifecycle(workspace).instance().instanceId();
        
        // 初始化运行时驱动和本地 Agent 运行时
        RuntimeDriver driver = new RuntimeDriver(store, instanceId);
        LocalAgentRuntime runtime = new LocalAgentRuntime(store, driver, graphs);
        
        // 设置遥测订阅者
        RuntimeEventOpenTelemetrySubscriber subscriber = new RuntimeEventOpenTelemetrySubscriber(
                telemetry.tracer("ricbot-runtime", ricbot.app.bootstrap.BuildVersion.current()));
        runtime.subscribe(subscriber);
        
        // 创建调用运行时服务
        AgentInvocationRuntime invocations = new AgentRuntimeExecutionService(runtime, graphFactory);
        
        // 初始化工人运行器和团队 Agent 图工厂
        ricbot.domain.agent.AgentTeamWorkerRunner workers = new ricbot.domain.agent.AgentTeamWorkerRunner(
                workspace, invocations, model, 8, 10_000, "standard", 64_000, null, null,
                budgetPolicy, offload, timezone, pricing);
        TeamAgentGraphFactory teamGraphs = new TeamAgentGraphFactory(workspace, store, provider,
                model, approvals, workers, budgetPolicy, pricing);
        
        // 配置团队图和统一图的关联
        teamGraphs.runtime(runtime);
        graphs.team(teamGraphs);
        
        // 注册受管运行时
        ricbot.app.bootstrap.RuntimeStoreRegistry.registerManaged(workspace, runtime);
        
        return new Components(runtime, invocations, subscriber);
    }

    public record Components(AgentRuntime runtime, AgentInvocationRuntime invocations,
                             RuntimeEventOpenTelemetrySubscriber telemetrySubscriber) { }
}
