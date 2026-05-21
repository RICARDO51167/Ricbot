package ricbot.integration.mcp;

/**
 * MCP server 连接对象。
 */
public interface MCPServerConnection extends AutoCloseable {

    MCPClientSession getSession();

    @Override
    void close() throws Exception;
}