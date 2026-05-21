package ricbot.integration.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端会话接口
 */
public interface MCPClientSession {

    void initialize() throws Exception;

    MCPToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception;

    MCPResourceResult readResource(String uri) throws Exception;

    MCPPromptResult getPrompt(String promptName, Map<String, Object> arguments) throws Exception;

    List<MCPToolDefinition> listTools() throws Exception;

    List<MCPResourceDefinition> listResources() throws Exception;

    List<MCPPromptDefinition> listPrompts() throws Exception;
}