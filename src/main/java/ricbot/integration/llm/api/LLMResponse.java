package ricbot.integration.llm.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对应 Python: LLMResponse
 *
 * 主要目标：
 * 1. 封装一次模型响应
 * 2. 统一文本、工具调用、用量、推理、错误字段
 */
public class LLMResponse {

    // 模型生成的文本内容
    private String content;
    // 模型发起的工具调用列表
    private List<ToolCallRequest> toolCalls = new ArrayList<>();
    // 响应结束的原因，默认为 "stop"
    private String finishReason = "stop";
    // Token 使用情况统计，如 prompt_tokens, completion_tokens 等
    private Map<String, Integer> usage = new HashMap<>();
    // 重试等待时间（秒），通常用于限流场景
    private Double retryAfter;
    // 模型的推理过程内容（如果支持）
    private String reasoningContent;
    // 思考块列表，用于存储结构化的思考过程
    private List<Map<String, Object>> thinkingBlocks;

    // 错误状态码
    private Integer errorStatusCode;
    // 错误种类
    private String errorKind;
    // 错误类型
    private String errorType;
    // 错误代码
    private String errorCode;
    // 错误后的重试等待时间（秒）
    private Double errorRetryAfterS;
    // 是否应该重试
    private Boolean errorShouldRetry;

    /**
     * 无参构造函数
     */
    public LLMResponse() {
    }

    /**
     * 带内容的构造函数
     *
     * @param content 模型生成的文本内容
     */
    public LLMResponse(String content) {
        this.content = content;
    }

    /**
     * 判断是否包含工具调用
     *
     * @return 如果工具调用列表不为空则返回 true
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * 获取模型生成的文本内容
     *
     * @return 文本内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 设置模型生成的文本内容
     *
     * @param content 文本内容
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setContent(String content) {
        this.content = content;
        return this;
    }

    /**
     * 获取工具调用列表
     *
     * @return 工具调用列表
     */
    public List<ToolCallRequest> getToolCalls() {
        return toolCalls;
    }

    /**
     * 设置工具调用列表
     *
     * @param toolCalls 工具调用列表，如果为 null 则初始化为空列表
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setToolCalls(List<ToolCallRequest> toolCalls) {
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
        return this;
    }

    /**
     * 获取响应结束原因
     *
     * @return 结束原因
     */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * 设置响应结束原因
     *
     * @param finishReason 结束原因
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setFinishReason(String finishReason) {
        this.finishReason = finishReason;
        return this;
    }

    /**
     * 获取 Token 使用情况
     *
     * @return Token 使用映射表
     */
    public Map<String, Integer> getUsage() {
        return usage;
    }

    /**
     * 设置 Token 使用情况
     *
     * @param usage Token 使用映射表，如果为 null 则初始化为空映射
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setUsage(Map<String, Integer> usage) {
        this.usage = usage != null ? usage : new HashMap<>();
        return this;
    }

    /**
     * 获取重试等待时间
     *
     * @return 重试等待时间（秒）
     */
    public Double getRetryAfter() {
        return retryAfter;
    }

    /**
     * 设置重试等待时间
     *
     * @param retryAfter 重试等待时间（秒）
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setRetryAfter(Double retryAfter) {
        this.retryAfter = retryAfter;
        return this;
    }

    /**
     * 获取推理过程内容
     *
     * @return 推理内容
     */
    public String getReasoningContent() {
        return reasoningContent;
    }

    /**
     * 设置推理过程内容
     *
     * @param reasoningContent 推理内容
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
        return this;
    }

    /**
     * 获取思考块列表
     *
     * @return 思考块列表
     */
    public List<Map<String, Object>> getThinkingBlocks() {
        return thinkingBlocks;
    }

    /**
     * 设置思考块列表
     *
     * @param thinkingBlocks 思考块列表
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setThinkingBlocks(List<Map<String, Object>> thinkingBlocks) {
        this.thinkingBlocks = thinkingBlocks;
        return this;
    }

    /**
     * 获取错误状态码
     *
     * @return 错误状态码
     */
    public Integer getErrorStatusCode() {
        return errorStatusCode;
    }

    /**
     * 设置错误状态码
     *
     * @param errorStatusCode 错误状态码
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setErrorStatusCode(Integer errorStatusCode) {
        this.errorStatusCode = errorStatusCode;
        return this;
    }

    /**
     * 获取错误种类
     *
     * @return 错误种类
     */
    public String getErrorKind() {
        return errorKind;
    }

    /**
     * 设置错误种类
     *
     * @param errorKind 错误种类
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setErrorKind(String errorKind) {
        this.errorKind = errorKind;
        return this;
    }

    /**
     * 获取错误类型
     *
     * @return 错误类型
     */
    public String getErrorType() {
        return errorType;
    }

    /**
     * 设置错误类型
     *
     * @param errorType 错误类型
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setErrorType(String errorType) {
        this.errorType = errorType;
        return this;
    }

    /**
     * 获取错误代码
     *
     * @return 错误代码
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * 设置错误代码
     *
     * @param errorCode 错误代码
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setErrorCode(String errorCode) {
        this.errorCode = errorCode;
        return this;
    }

    /**
     * 获取错误后的重试等待时间
     *
     * @return 重试等待时间（秒）
     */
    public Double getErrorRetryAfterS() {
        return errorRetryAfterS;
    }

    /**
     * 设置错误后的重试等待时间
     *
     * @param errorRetryAfterS 重试等待时间（秒）
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setErrorRetryAfterS(Double errorRetryAfterS) {
        this.errorRetryAfterS = errorRetryAfterS;
        return this;
    }

    /**
     * 获取是否应该重试的标志
     *
     * @return 是否应该重试
     */
    public Boolean getErrorShouldRetry() {
        return errorShouldRetry;
    }

    /**
     * 设置是否应该重试的标志
     *
     * @param errorShouldRetry 是否应该重试
     * @return 当前对象实例，支持链式调用
     */
    public LLMResponse setErrorShouldRetry(Boolean errorShouldRetry) {
        this.errorShouldRetry = errorShouldRetry;
        return this;
    }

    @Override
    public String toString() {
        return "LLM响应{" +
                "内容='" + content + '\'' +
                ", 工具调用=" + toolCalls +
                ", 结束原因='" + finishReason + '\'' +
                '}';
    }
}