package ricbot.integration.mcp;

/**
 * MCP server 连接对象，占位版。
 *
 * 负责：
 * - 提供 session
 * - 关闭底层连接
 */
public interface MCPServerConnection extends AutoCloseable {

    /**
     * 获取 MCP 客户端会话对象。
     *
     * @return MCPClientSession 会话实例
     */
    MCPClientSession getSession();

    /**
     * 关闭底层连接并释放相关资源。
     *
     * @throws Exception 关闭过程中可能抛出的异常
     */
    @Override
    void close() throws Exception;
}