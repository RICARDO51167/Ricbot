package ricbot.integration.llm.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用请求，支持转换为 OpenAI 风格的 tool_call 结构
 */
public class ToolCallRequest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String id;

    private String name;

    private Map<String, Object> arguments = new LinkedHashMap<>();


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

    public Map<String, Object> toOpenAIToolCall() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        try {
            function.put("arguments", MAPPER.writeValueAsString(arguments));
        } catch (JsonProcessingException e) {
            function.put("arguments", "{}");
        }

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put("type", "function");
        toolCall.put("function", function);
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
