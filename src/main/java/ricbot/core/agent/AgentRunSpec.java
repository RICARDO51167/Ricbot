package ricbot.core.agent;


import ricbot.core.hook.AgentHook;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * AgentRunner 的运行参数
 *
 * 主要目标：
 * 1. 封装一次 runner.run(...) 所需的全部参数
 *
 * 对应 Python: AgentRunSpec
 */
public class AgentRunSpec {

    private List<Map<String, Object>> initialMessages = new ArrayList<>();
    private ToolRegistry tools;
    private String model;
    private int maxIterations = 20;
    private int maxToolResultChars = 16000;
    private AgentHook hook;
    private String errorMessage = "Sorry, I encountered an error calling the AI model.";
    private String maxIterationsMessage =
            "I reached the maximum number of tool call iterations without completing the task.";
    private boolean failOnToolError = false;
    private boolean concurrentTools = false;
    private Path workspace;
    private String sessionKey;
    private Integer contextWindowTokens;
    private Integer contextBlockLimit;
    private String providerRetryMode = "standard";

    /**
     * checkpoint 回调
     */
    private Consumer<Map<String, Object>> checkpointCallback;

    /**
     * 进度回调
     */
    private ProgressCallback progressCallback;

    /**
     * 注入 follow-up user message 的回调
     */
    private InjectionCallback injectionCallback;

    public List<Map<String, Object>> getInitialMessages() {
        return initialMessages;
    }

    public AgentRunSpec setInitialMessages(List<Map<String, Object>> initialMessages) {
        this.initialMessages = initialMessages != null ? initialMessages : new ArrayList<>();
        return this;
    }

    public ToolRegistry getTools() {
        return tools;
    }

    public AgentRunSpec setTools(ToolRegistry tools) {
        this.tools = tools;
        return this;
    }

    public String getModel() {
        return model;
    }

    public AgentRunSpec setModel(String model) {
        this.model = model;
        return this;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public AgentRunSpec setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
        return this;
    }

    public int getMaxToolResultChars() {
        return maxToolResultChars;
    }

    public AgentRunSpec setMaxToolResultChars(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
        return this;
    }

    public AgentHook getHook() {
        return hook;
    }

    public AgentRunSpec setHook(AgentHook hook) {
        this.hook = hook;
        return this;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public AgentRunSpec setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        return this;
    }

    public String getMaxIterationsMessage() {
        return maxIterationsMessage;
    }

    public AgentRunSpec setMaxIterationsMessage(String maxIterationsMessage) {
        this.maxIterationsMessage = maxIterationsMessage;
        return this;
    }

    public boolean isFailOnToolError() {
        return failOnToolError;
    }

    public AgentRunSpec setFailOnToolError(boolean failOnToolError) {
        this.failOnToolError = failOnToolError;
        return this;
    }

    public boolean isConcurrentTools() {
        return concurrentTools;
    }

    public AgentRunSpec setConcurrentTools(boolean concurrentTools) {
        this.concurrentTools = concurrentTools;
        return this;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public AgentRunSpec setWorkspace(Path workspace) {
        this.workspace = workspace;
        return this;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public AgentRunSpec setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
        return this;
    }

    public Integer getContextWindowTokens() {
        return contextWindowTokens;
    }

    public AgentRunSpec setContextWindowTokens(Integer contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
        return this;
    }

    public Integer getContextBlockLimit() {
        return contextBlockLimit;
    }

    public AgentRunSpec setContextBlockLimit(Integer contextBlockLimit) {
        this.contextBlockLimit = contextBlockLimit;
        return this;
    }

    public String getProviderRetryMode() {
        return providerRetryMode;
    }

    public AgentRunSpec setProviderRetryMode(String providerRetryMode) {
        this.providerRetryMode = providerRetryMode;
        return this;
    }

    public Consumer<Map<String, Object>> getCheckpointCallback() {
        return checkpointCallback;
    }

    public AgentRunSpec setCheckpointCallback(Consumer<Map<String, Object>> checkpointCallback) {
        this.checkpointCallback = checkpointCallback;
        return this;
    }

    public ProgressCallback getProgressCallback() {
        return progressCallback;
    }

    public AgentRunSpec setProgressCallback(ProgressCallback progressCallback) {
        this.progressCallback = progressCallback;
        return this;
    }

    public InjectionCallback getInjectionCallback() {
        return injectionCallback;
    }

    public AgentRunSpec setInjectionCallback(InjectionCallback injectionCallback) {
        this.injectionCallback = injectionCallback;
        return this;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String content, boolean toolHint) throws Exception;
    }

    @FunctionalInterface
    public interface InjectionCallback {
        List<Map<String, Object>> inject() throws Exception;
    }
}