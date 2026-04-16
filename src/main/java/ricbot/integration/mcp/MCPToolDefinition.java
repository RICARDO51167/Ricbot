package ricbot.integration.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MCPToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }
}

/**
 * MCP 工具结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPToolResult {
    private List<Object> content;

    public List<Object> getContent() { return content; }
    public void setContent(List<Object> content) { this.content = content; }
}

/**
 * MCP Prompt 参数
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPPromptArgument {
    private String name;
    private String description;
    private boolean required;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
}

/**
 * MCP Prompt 定义
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPPromptDefinition {
    private String name;
    private String description;
    private List<MCPPromptArgument> arguments;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<MCPPromptArgument> getArguments() { return arguments; }
    public void setArguments(List<MCPPromptArgument> arguments) { this.arguments = arguments; }
}

/**
 * MCP Prompt 消息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPPromptMessage {
    private Object content;

    public Object getContent() { return content; }
    public void setContent(Object content) { this.content = content; }
}

/**
 * MCP Prompt 结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPPromptResult {
    private List<MCPPromptMessage> messages;

    public List<MCPPromptMessage> getMessages() { return messages; }
    public void setMessages(List<MCPPromptMessage> messages) { this.messages = messages; }
}

/**
 * MCP 资源定义
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPResourceDefinition {
    private String name;
    private String description;
    private String uri;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
}

/**
 * MCP 资源结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPResourceResult {
    private List<Object> contents;

    public List<Object> getContents() { return contents; }
    public void setContents(List<Object> contents) { this.contents = contents; }
}

/**
 * MCP 文本内容
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPTextContent {
    private String text;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}

/**
 * MCP 文本资源内容
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPTextResourceContents {
    private String text;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}

/**
 * MCP Blob 资源内容
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MCPBlobResourceContents {
    private int blobLength;

    public int getBlobLength() { return blobLength; }
    public void setBlobLength(int blobLength) { this.blobLength = blobLength; }
}
