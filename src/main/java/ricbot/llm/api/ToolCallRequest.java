package ricbot.llm.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对应 Python: ToolCallRequest
 *
 * 主要目标：
 * 1. 表示一次模型发起的工具调用请求
 * 2. 支持转成 OpenAI 风格 tool_call 结构
 */
public class ToolCallRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 工具调用 ID
     */
    private String id;

    /**
     * 工具名
     */
    private String name;

    /**
     * 工具参数
     */
    private Map<String, Object> arguments = new LinkedHashMap<>();

    /**
     * 额外内容
     */
    private Map<String, Object> extraContent;

    /**
     * provider 级额外字段
     */
    private Map<String, Object> providerSpecificFields;

    /**
     * function 级额外字段
     */
    private Map<String, Object> functionProviderSpecificFields;

    public ToolCallRequest() {
    }

    public ToolCallRequest(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments != null ? arguments : new LinkedHashMap<>();
    }

    public String getId() {
        return id;
    }

    public ToolCallRequest setId(String id) {
        this.id = id;
        return this;
    }

    public String getName() {
        return name;
    }

    public ToolCallRequest setName(String name) {
        this.name = name;
        return this;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public ToolCallRequest setArguments(Map<String, Object> arguments) {
        this.arguments = arguments != null ? arguments : new LinkedHashMap<>();
        return this;
    }

    public Map<String, Object> getExtraContent() {
        return extraContent;
    }

    public ToolCallRequest setExtraContent(Map<String, Object> extraContent) {
        this.extraContent = extraContent;
        return this;
    }

    public Map<String, Object> getProviderSpecificFields() {
        return providerSpecificFields;
    }

    public ToolCallRequest setProviderSpecificFields(Map<String, Object> providerSpecificFields) {
        this.providerSpecificFields = providerSpecificFields;
        return this;
    }

    public Map<String, Object> getFunctionProviderSpecificFields() {
        return functionProviderSpecificFields;
    }

    public ToolCallRequest setFunctionProviderSpecificFields(Map<String, Object> functionProviderSpecificFields) {
        this.functionProviderSpecificFields = functionProviderSpecificFields;
        return this;
    }

    /**
     * 对应 Python:
     * to_openai_tool_call()
     */
    public Map<String, Object> toOpenAIToolCall() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);

        try {
            function.put("arguments", MAPPER.writeValueAsString(arguments));
        } catch (JsonProcessingException e) {
            function.put("arguments", "{}");
        }

        if (functionProviderSpecificFields != null && !functionProviderSpecificFields.isEmpty()) {
            function.put("provider_specific_fields", functionProviderSpecificFields);
        }

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put("type", "function");
        toolCall.put("function", function);

        if (extraContent != null && !extraContent.isEmpty()) {
            toolCall.put("extra_content", extraContent);
        }
        if (providerSpecificFields != null && !providerSpecificFields.isEmpty()) {
            toolCall.put("provider_specific_fields", providerSpecificFields);
        }

        return toolCall;
    }

    @Override
    public String toString() {
        return "ToolCallRequest{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", arguments=" + arguments +
                '}';
    }
}