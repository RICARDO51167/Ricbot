package ricbot.tool.mcp;

/**
 * MCP server 连接对象，占位版。
 *
 * 负责：
 * - 提供 session
 * - 关闭底层连接
 */
public interface MCPServerConnection extends AutoCloseable {

    MCPClientSession getSession();

    @Override
    void close() throws Exception;
}