package ricbot.llm.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对应 Python: LLMResponse
 *
 * 主要目标：
 * 1. 封装一次模型响应
 * 2. 统一文本、工具调用、usage、reasoning、错误字段
 */
public class LLMResponse {

    private String content;
    private List<ToolCallRequest> toolCalls = new ArrayList<>();
    private String finishReason = "stop";
    private Map<String, Integer> usage = new HashMap<>();
    private Double retryAfter;
    private String reasoningContent;
    private List<Map<String, Object>> thinkingBlocks;

    private Integer errorStatusCode;
    private String errorKind;
    private String errorType;
    private String errorCode;
    private Double errorRetryAfterS;
    private Boolean errorShouldRetry;

    public LLMResponse() {
    }

    public LLMResponse(String content) {
        this.content = content;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public String getContent() {
        return content;
    }

    public LLMResponse setContent(String content) {
        this.content = content;
        return this;
    }

    public List<ToolCallRequest> getToolCalls() {
        return toolCalls;
    }

    public LLMResponse setToolCalls(List<ToolCallRequest> toolCalls) {
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
        return this;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public LLMResponse setFinishReason(String finishReason) {
        this.finishReason = finishReason;
        return this;
    }

    public Map<String, Integer> getUsage() {
        return usage;
    }

    public LLMResponse setUsage(Map<String, Integer> usage) {
        this.usage = usage != null ? usage : new HashMap<>();
        return this;
    }

    public Double getRetryAfter() {
        return retryAfter;
    }

    public LLMResponse setRetryAfter(Double retryAfter) {
        this.retryAfter = retryAfter;
        return this;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public LLMResponse setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
        return this;
    }

    public List<Map<String, Object>> getThinkingBlocks() {
        return thinkingBlocks;
    }

    public LLMResponse setThinkingBlocks(List<Map<String, Object>> thinkingBlocks) {
        this.thinkingBlocks = thinkingBlocks;
        return this;
    }

    public Integer getErrorStatusCode() {
        return errorStatusCode;
    }

    public LLMResponse setErrorStatusCode(Integer errorStatusCode) {
        this.errorStatusCode = errorStatusCode;
        return this;
    }

    public String getErrorKind() {
        return errorKind;
    }

    public LLMResponse setErrorKind(String errorKind) {
        this.errorKind = errorKind;
        return this;
    }

    public String getErrorType() {
        return errorType;
    }

    public LLMResponse setErrorType(String errorType) {
        this.errorType = errorType;
        return this;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public LLMResponse setErrorCode(String errorCode) {
        this.errorCode = errorCode;
        return this;
    }

    public Double getErrorRetryAfterS() {
        return errorRetryAfterS;
    }

    public LLMResponse setErrorRetryAfterS(Double errorRetryAfterS) {
        this.errorRetryAfterS = errorRetryAfterS;
        return this;
    }

    public Boolean getErrorShouldRetry() {
        return errorShouldRetry;
    }

    public LLMResponse setErrorShouldRetry(Boolean errorShouldRetry) {
        this.errorShouldRetry = errorShouldRetry;
        return this;
    }

    @Override
    public String toString() {
        return "LLMResponse{" +
                "content='" + content + '\'' +
                ", toolCalls=" + toolCalls +
                ", finishReason='" + finishReason + '\'' +
                '}';
    }
}