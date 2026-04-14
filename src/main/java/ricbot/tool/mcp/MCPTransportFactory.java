package ricbot.tool.mcp;

import ricbot.infra.config.Config;

/**
 * MCP Transport 工厂，占位版。
 *
 * 你后面可以分别实现：
 * - stdio
 * - sse
 * - streamableHttp
 */
public final class MCPTransportFactory {

    private MCPTransportFactory() {
    }

    public static MCPServerConnection connectStdio(Config.MCPServerConfig cfg) {
        throw new UnsupportedOperationException("connectStdio not implemented yet");
    }

    public static MCPServerConnection connectSse(Config.MCPServerConfig cfg) {
        throw new UnsupportedOperationException("connectSse not implemented yet");
    }

    public static MCPServerConnection connectStreamableHttp(Config.MCPServerConfig cfg) {
        throw new UnsupportedOperationException("connectStreamableHttp not implemented yet");
    }
}