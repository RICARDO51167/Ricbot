package ricbot.integration.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 会话接口，占位版。
 *
 * 你后面真正接 SDK 时，只要适配成这个接口即可。
 */
public interface MCPClientSession {

    /**
     * 初始化 MCP 会话。
     *
     * @throws Exception 初始化失败时抛出异常
     */
    void initialize() throws Exception;

    /**
     * 调用指定的 MCP 工具。
     *
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     * @throws Exception 调用失败时抛出异常
     */
    MCPToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception;

    /**
     * 读取指定的 MCP 资源。
     *
     * @param uri 资源 URI
     * @return 资源内容结果
     * @throws Exception 读取失败时抛出异常
     */
    MCPResourceResult readResource(String uri) throws Exception;

    /**
     * 获取指定的 MCP 提示词。
     *
     * @param promptName 提示词名称
     * @param arguments 提示词参数
     * @return 提示词结果
     * @throws Exception 获取失败时抛出异常
     */
    MCPPromptResult getPrompt(String promptName, Map<String, Object> arguments) throws Exception;

    /**
     * 列出所有可用的 MCP 工具定义。
     *
     * @return 工具定义列表
     * @throws Exception 获取失败时抛出异常
     */
    List<MCPToolDefinition> listTools() throws Exception;

    /**
     * 列出所有可用的 MCP 资源定义。
     *
     * @return 资源定义列表
     * @throws Exception 获取失败时抛出异常
     */
    List<MCPResourceDefinition> listResources() throws Exception;

    /**
     * 列出所有可用的 MCP 提示词定义。
     *
     * @return 提示词定义列表
     * @throws Exception 获取失败时抛出异常
     */
    List<MCPPromptDefinition> listPrompts() throws Exception;
}