package ricbot.domain.agent;

import ricbot.domain.agent.dto.ExecutionOutcome;
import ricbot.domain.agent.interfacep.AgentInvocationRuntime;
import ricbot.domain.config.ProviderCapability;
import ricbot.domain.config.ModelCard;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.security.ApprovalService;
import ricbot.infra.runtime.RuntimeUtils;
import ricbot.infra.config.Config;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.Map;

/** Builds immutable invocation input and delegates once to the durable runtime. */
final class AgentExecutionService {
    private final AgentInvocationRuntime runner;
    private final ToolRegistry tools;
    private final Path workspace;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final String providerRetryMode;
    private final int contextWindowTokens;
    private final Integer contextBlockLimit;
    private final ProviderCapability providerCapability;
    private final ApprovalService approvalService;
    private final BudgetPolicy budgetPolicy;
    private final Config.ContextOffloadConfig offload;
    private final String timezone;
    private ModelCard.Pricing modelPricing;
    private Config.ContextManagementConfig contextManagement = new Config.ContextManagementConfig();
    private Config.ToolRuntimeConfig toolRuntime = new Config.ToolRuntimeConfig();

    AgentExecutionService(AgentInvocationRuntime runner, ToolRegistry tools, Path workspace, String model,
                          int maxIterations, int maxToolResultChars, String providerRetryMode,
                          int contextWindowTokens, Integer contextBlockLimit,
                          ProviderCapability capability, ApprovalService approvals) {
        this(runner, tools, workspace, model, maxIterations, maxToolResultChars, providerRetryMode,
                contextWindowTokens, contextBlockLimit, capability, approvals,
                BudgetPolicy.unlimited(), new Config.ContextOffloadConfig(), "UTC");
    }

    AgentExecutionService(AgentInvocationRuntime runner, ToolRegistry tools, Path workspace, String model,
                          int maxIterations, int maxToolResultChars, String providerRetryMode,
                          int contextWindowTokens, Integer contextBlockLimit,
                          ProviderCapability capability, ApprovalService approvals, BudgetPolicy budgetPolicy,
                          Config.ContextOffloadConfig offload, String timezone) {
        this.runner = runner;
        this.tools = tools;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
        this.maxToolResultChars = maxToolResultChars;
        this.providerRetryMode = providerRetryMode;
        this.contextWindowTokens = contextWindowTokens;
        this.contextBlockLimit = contextBlockLimit;
        this.providerCapability = capability;
        this.approvalService = java.util.Objects.requireNonNull(approvals, "approvals");
        this.budgetPolicy = budgetPolicy != null ? budgetPolicy : BudgetPolicy.unlimited();
        this.offload = offload != null ? offload : new Config.ContextOffloadConfig();
        this.timezone = timezone != null && !timezone.isBlank() ? timezone : "UTC";
    }

    ExecutionOutcome executeInteractive(AgentRequestContext request) throws Exception {
        AgentRunResult result = runner.run(buildSpec(request, request.hook()));
        String content = RuntimeUtils.isBlankText(result.getFinalContent())
                ? RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE : result.getFinalContent();
        return new ExecutionOutcome(result, content);
    }

    ExecutionOutcome executeSystem(AgentRequestContext request) throws Exception {
        AgentRunResult result = runner.run(buildSpec(request, null));
        String content = RuntimeUtils.isBlankText(result.getFinalContent()) ? "后台任务已完成。" : result.getFinalContent();
        return new ExecutionOutcome(result, content);
    }

    private AgentRunSpec buildSpec(AgentRequestContext request, AgentHook hook) {
        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(request.initialMessages()).setTools(tools).setModel(model)
                .setMaxIterations(maxIterations).setMaxToolResultChars(maxToolResultChars).setHook(hook)
                .setProviderRetryMode(providerRetryMode).setErrorMessage("抱歉，调用模型时遇到错误。")
                .setMaxIterationsMessage("我已达到最大迭代次数（agents.defaults.max_tool_iterations="
                        + maxIterations + "），但仍未完成任务。")
                .setWorkspace(workspace).setSessionKey(request.session().getKey())
                .setContextWindowTokens(contextWindowTokens).setContextBlockLimit(contextBlockLimit)
                .setProviderCapability(providerCapability)
                .setModelPricing(modelPricing)
                .setApprovalService(approvalService).setBudgetPolicy(budgetPolicy)
                .setContextOffloadEnabled(offload.isEnabled()).setOffloadPreviewChars(offload.getPreviewChars())
                .setArtifactReadChunkChars(offload.getReadChunkChars())
                .setMaxArtifactBytesPerTool(offload.getMaxArtifactBytesPerTool())
                .setContextTriggerRatio(contextManagement.getTriggerRatio())
                .setContextWarningRatio(contextManagement.getWarningRatio())
                .setContextTargetRatio(contextManagement.getTargetRatio())
                .setTimeHintIntervalMinutes(contextManagement.getTimeHintIntervalMinutes())
                .setExternalActionsEnabled(toolRuntime.isExternalActionsEnabled())
                .setMaxParallelReadCalls(toolRuntime.getMaxParallelReadCalls())
                .setRequireReadReceipt(toolRuntime.isRequireReadReceipt()).setTimezone(timezone)
                .setToolLifecycleCallback(new AgentRunSpec.ToolLifecycleCallback() {
                    public void onToolStart(String name, Map<String, Object> arguments) {
                        synchronized (request.session()) {
                            TaskState state = TaskState.fromSession(request.session());
                            state.markToolStart(name, arguments); state.persist(request.session());
                        }
                    }
                    public void onToolFinish(Map<String, Object> event) {
                        synchronized (request.session()) {
                            TaskState state = TaskState.fromSession(request.session());
                            state.markToolFinish(event); state.persist(request.session());
                        }
                    }
                });
        if (request.message().getMetadata() != null) request.message().getMetadata().forEach((key, value) -> {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
                spec.getMetadata().put(key, value);
        });
        return spec;
    }

    void setModelPricing(ModelCard.Pricing modelPricing) { this.modelPricing = modelPricing; }
    void setRuntimeConfigs(Config.ContextManagementConfig contextManagement,
                           Config.ToolRuntimeConfig toolRuntime) {
        this.contextManagement = contextManagement != null ? contextManagement : new Config.ContextManagementConfig();
        this.toolRuntime = toolRuntime != null ? toolRuntime : new Config.ToolRuntimeConfig();
    }
}
