package ricbot.domain.agent;

import ricbot.domain.config.ProviderCapability;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.security.ApprovalService;
import ricbot.infra.runtime.RuntimeUtils;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** Builds immutable invocation input and delegates once to the graph runtime. */
final class AgentExecutionService {
    private final GraphRunService runner;
    private final ToolRegistry tools;
    private final Path workspace;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final String providerRetryMode;
    private final int contextWindowTokens;
    private final Integer contextBlockLimit;
    private final ProviderCapability providerCapability;
    private final SideEffectStore sideEffectStore;
    private final ApprovalService approvalService;

    AgentExecutionService(GraphRunService runner, ToolRegistry tools, Path workspace, String model,
                          int maxIterations, int maxToolResultChars, String providerRetryMode,
                          int contextWindowTokens, Integer contextBlockLimit) {
        this(runner, tools, workspace, model, maxIterations, maxToolResultChars, providerRetryMode,
                contextWindowTokens, contextBlockLimit, null, SideEffectStore.disabled(), null);
    }

    AgentExecutionService(GraphRunService runner, ToolRegistry tools, Path workspace, String model,
                          int maxIterations, int maxToolResultChars, String providerRetryMode,
                          int contextWindowTokens, Integer contextBlockLimit,
                          ProviderCapability capability) {
        this(runner, tools, workspace, model, maxIterations, maxToolResultChars, providerRetryMode,
                contextWindowTokens, contextBlockLimit, capability, SideEffectStore.disabled(), null);
    }

    AgentExecutionService(GraphRunService runner, ToolRegistry tools, Path workspace, String model,
                          int maxIterations, int maxToolResultChars, String providerRetryMode,
                          int contextWindowTokens, Integer contextBlockLimit,
                          ProviderCapability capability, SideEffectStore effects,
                          ApprovalService approvals) {
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
        this.sideEffectStore = effects != null ? effects : SideEffectStore.disabled();
        this.approvalService = approvals;
    }

    ExecutionOutcome executeInteractive(AgentRequestContext request,
                                        Consumer<Map<String, Object>> ignoredLegacyCheckpoint) throws Exception {
        AgentRunResult result = runner.run(buildSpec(request, request.hook()));
        String content = RuntimeUtils.isBlankText(result.getFinalContent())
                ? RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE : result.getFinalContent();
        return new ExecutionOutcome(result, content);
    }

    ExecutionOutcome executeSystem(AgentRequestContext request) throws Exception { return executeSystem(request, null); }

    ExecutionOutcome executeSystem(AgentRequestContext request,
                                   Consumer<Map<String, Object>> ignoredLegacyCheckpoint) throws Exception {
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
                .setConcurrentTools(true).setWorkspace(workspace).setSessionKey(request.session().getKey())
                .setContextWindowTokens(contextWindowTokens).setContextBlockLimit(contextBlockLimit)
                .setProviderCapability(providerCapability).setSideEffectStore(sideEffectStore)
                .setApprovalService(approvalService)
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
}
