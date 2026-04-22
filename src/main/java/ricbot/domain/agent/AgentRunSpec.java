package ricbot.domain.agent;


import ricbot.domain.hook.AgentHook;
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

    // 初始消息列表，用于启动 Agent 对话
    private List<Map<String, Object>> initialMessages = new ArrayList<>();
    // 工具注册表，包含 Agent 可调用的所有工具
    private ToolRegistry tools;
    // 使用的模型名称
    private String model;
    // 最大迭代次数，防止无限循环
    private int maxIterations = 20;
    // 工具结果的最大字符数限制
    private int maxToolResultChars = 16000;
    // Agent 钩子，用于拦截和处理 Agent 生命周期事件
    private AgentHook hook;
    // 发生错误时的默认错误消息
    private String errorMessage = "抱歉，我在调用 AI 模型时遇到了错误。";
    // 达到最大迭代次数时的提示消息
    private String maxIterationsMessage =
            "我已达到最大工具调用迭代次数，但仍未完成任务。";
    // 是否在工具执行出错时立即失败
    private boolean failOnToolError = false;
    // 是否允许并发执行工具
    private boolean concurrentTools = false;
    // 工作空间路径
    private Path workspace;
    // 会话密钥，用于标识和隔离不同会话
    private String sessionKey;
    // 上下文窗口令牌数限制
    private Integer contextWindowTokens;
    // 上下文块数量限制
    private Integer contextBlockLimit;
    // 提供商重试模式，默认为标准模式
    private String providerRetryMode = "standard";

    /**
     * checkpoint 回调，用于保存中间状态
     */
    private Consumer<Map<String, Object>> checkpointCallback;

    /**
     * 进度回调，用于报告执行进度
     */
    private ProgressCallback progressCallback;

    /**
     * 注入 follow-up user message 的回调，用于动态插入用户消息
     */
    private InjectionCallback injectionCallback;

    private ToolLifecycleCallback toolLifecycleCallback;

    /**
     * 获取初始消息列表
     * @return 初始消息列表
     */
    public List<Map<String, Object>> getInitialMessages() {
        return initialMessages;
    }

    /**
     * 设置初始消息列表
     * @param initialMessages 初始消息列表
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setInitialMessages(List<Map<String, Object>> initialMessages) {
        this.initialMessages = initialMessages != null ? initialMessages : new ArrayList<>();
        return this;
    }

    /**
     * 获取工具注册表
     * @return 工具注册表
     */
    public ToolRegistry getTools() {
        return tools;
    }

    /**
     * 设置工具注册表
     * @param tools 工具注册表
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setTools(ToolRegistry tools) {
        this.tools = tools;
        return this;
    }

    /**
     * 获取模型名称
     * @return 模型名称
     */
    public String getModel() {
        return model;
    }

    /**
     * 设置模型名称
     * @param model 模型名称
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setModel(String model) {
        this.model = model;
        return this;
    }

    /**
     * 获取最大迭代次数
     * @return 最大迭代次数
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * 设置最大迭代次数
     * @param maxIterations 最大迭代次数
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
        return this;
    }

    /**
     * 获取工具结果的最大字符数限制
     * @return 最大字符数限制
     */
    public int getMaxToolResultChars() {
        return maxToolResultChars;
    }

    /**
     * 设置工具结果的最大字符数限制
     * @param maxToolResultChars 最大字符数限制
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setMaxToolResultChars(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
        return this;
    }

    /**
     * 获取 Agent 钩子
     * @return Agent 钩子
     */
    public AgentHook getHook() {
        return hook;
    }

    /**
     * 设置 Agent 钩子
     * @param hook Agent 钩子
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setHook(AgentHook hook) {
        this.hook = hook;
        return this;
    }

    /**
     * 获取错误消息
     * @return 错误消息
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 设置错误消息
     * @param errorMessage 错误消息
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        return this;
    }

    /**
     * 获取达到最大迭代次数时的提示消息
     * @return 提示消息
     */
    public String getMaxIterationsMessage() {
        return maxIterationsMessage;
    }

    /**
     * 设置达到最大迭代次数时的提示消息
     * @param maxIterationsMessage 提示消息
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setMaxIterationsMessage(String maxIterationsMessage) {
        this.maxIterationsMessage = maxIterationsMessage;
        return this;
    }

    /**
     * 检查是否在工具执行出错时立即失败
     * @return 是否立即失败
     */
    public boolean isFailOnToolError() {
        return failOnToolError;
    }

    /**
     * 设置是否在工具执行出错时立即失败
     * @param failOnToolError 是否立即失败
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setFailOnToolError(boolean failOnToolError) {
        this.failOnToolError = failOnToolError;
        return this;
    }

    /**
     * 检查是否允许并发执行工具
     * @return 是否允许并发
     */
    public boolean isConcurrentTools() {
        return concurrentTools;
    }

    /**
     * 设置是否允许并发执行工具
     * @param concurrentTools 是否允许并发
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setConcurrentTools(boolean concurrentTools) {
        this.concurrentTools = concurrentTools;
        return this;
    }

    /**
     * 获取工作空间路径
     * @return 工作空间路径
     */
    public Path getWorkspace() {
        return workspace;
    }

    /**
     * 设置工作空间路径
     * @param workspace 工作空间路径
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setWorkspace(Path workspace) {
        this.workspace = workspace;
        return this;
    }

    /**
     * 获取会话密钥
     * @return 会话密钥
     */
    public String getSessionKey() {
        return sessionKey;
    }

    /**
     * 设置会话密钥
     * @param sessionKey 会话密钥
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
        return this;
    }

    /**
     * 设置上下文窗口令牌数限制
     * @param contextWindowTokens 令牌数限制
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setContextWindowTokens(Integer contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
        return this;
    }

    /**
     * 获取上下文块数量限制
     * @return 块数量限制
     */
    public Integer getContextBlockLimit() {
        return contextBlockLimit;
    }

    /**
     * 设置上下文块数量限制
     * @param contextBlockLimit 块数量限制
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setContextBlockLimit(Integer contextBlockLimit) {
        this.contextBlockLimit = contextBlockLimit;
        return this;
    }

    /**
     * 获取提供商重试模式
     * @return 重试模式
     */
    public String getProviderRetryMode() {
        return providerRetryMode;
    }

    /**
     * 设置提供商重试模式
     * @param providerRetryMode 重试模式
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setProviderRetryMode(String providerRetryMode) {
        this.providerRetryMode = providerRetryMode;
        return this;
    }

    /**
     * 获取 checkpoint 回调
     * @return checkpoint 回调
     */
    public Consumer<Map<String, Object>> getCheckpointCallback() {
        return checkpointCallback;
    }

    /**
     * 设置 checkpoint 回调
     * @param checkpointCallback checkpoint 回调
     * @return 当前对象实例，支持链式调用
     */
    public AgentRunSpec setCheckpointCallback(Consumer<Map<String, Object>> checkpointCallback) {
        this.checkpointCallback = checkpointCallback;
        return this;
    }

    /**
     * 获取注入回调
     * @return 注入回调
     */
    public InjectionCallback getInjectionCallback() {
        return injectionCallback;
    }

    public ProgressCallback getProgressCallback() {
        return progressCallback;
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        this.progressCallback = progressCallback;
    }

    public void setInjectionCallback(InjectionCallback injectionCallback) {
        this.injectionCallback = injectionCallback;
    }

    public Integer getContextWindowTokens() {
        return contextWindowTokens;
    }

    public ToolLifecycleCallback getToolLifecycleCallback() {
        return toolLifecycleCallback;
    }

    public AgentRunSpec setToolLifecycleCallback(ToolLifecycleCallback toolLifecycleCallback) {
        this.toolLifecycleCallback = toolLifecycleCallback;
        return this;
    }

    /**
     * 进度回调函数式接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * 当有进度更新时调用
         * @param content 进度内容
         * @param toolHint 是否为工具提示
         * @throws Exception 异常
         */
        void onProgress(String content, boolean toolHint) throws Exception;
    }

    /**
     * 注入回调函数式接口
     */
    @FunctionalInterface
    public interface InjectionCallback {
        /**
         * 注入额外的用户消息
         * @return 消息列表
         * @throws Exception 异常
         */
        List<Map<String, Object>> inject() throws Exception;
    }

    interface ToolLifecycleCallback {
        void onToolStart(String toolName, Map<String, Object> arguments);

        void onToolFinish(Map<String, Object> event);
    }
}
