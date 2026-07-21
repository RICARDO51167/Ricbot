package ricbot.integration.mcp;

import org.junit.jupiter.api.Test;
import ricbot.infra.config.Config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MCPAdaptersTest {

    @Test
    void parseMcpServers_supportsSnakeCaseAndTypes() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("demo", Map.of(
                "type", "stdio",
                "command", "node",
                "args", List.of("server.js", "--port", 1234),
                "env", Map.of("A", "B"),
                "enabled_tools", List.of("*"),
                "tool_timeout", 30
        ));

        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(raw);
        assertTrue(parsed.containsKey("demo"));

        Config.MCPServerConfig cfg = parsed.get("demo");
        assertEquals("stdio", cfg.getType());
        assertEquals("node", cfg.getCommand());
        assertEquals(List.of("server.js", "--port", "1234"), cfg.getArgs());
        assertEquals(Map.of("A", "B"), cfg.getEnv());
        assertEquals(List.of("*"), cfg.getEnabledTools());
        assertEquals(30, cfg.getToolTimeout());
    }

    @Test
    void parseMcpServers_supportsCamelCaseFallbacks() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("demo", Map.of(
                "command", "python",
                "args", "server.py",
                "enabledTools", List.of("toolA"),
                "toolTimeout", "45"
        ));

        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(raw);
        Config.MCPServerConfig cfg = parsed.get("demo");

        assertEquals("python", cfg.getCommand());
        assertEquals(List.of("server.py"), cfg.getArgs());
        assertEquals(List.of("toolA"), cfg.getEnabledTools());
        assertEquals(45, cfg.getToolTimeout());
    }

    @Test
    void parseMcpServers_supportsQuotedStringsAndCsvLists() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("demo", Map.of(
                "command", "`node`",
                "enabled_tools", "\"toolA\", 'toolB'"
        ));

        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(raw);
        Config.MCPServerConfig cfg = parsed.get("demo");

        assertEquals("node", cfg.getCommand());
        assertEquals(List.of("toolA", "toolB"), cfg.getEnabledTools());
    }

    @Test
    void removedSseTransportReturnsMigrationGuidance() {
        Config.MCPServerConfig cfg = new Config.MCPServerConfig();
        cfg.setType("sse");
        cfg.setUrl("https://example.test/sse");

        MCPAdapters.MCPConnectReport report = MCPAdapters.connectMcpServersDetailed(
                Map.of("legacy", cfg),
                new ricbot.tool.api.ToolRegistry()
        );

        MCPAdapters.MCPServerLoadInfo info = report.servers().get("legacy");
        assertNotNull(info);
        assertEquals("FAILED", info.status());
        assertTrue(info.lastError().contains("已删除"), info.lastError());
        assertTrue(info.lastError().contains("streamableHttp"), info.lastError());
    }

    @Test
    void healthReport_returnsStructuredStatus() {
        MCPServerConnection ok = connectionWithSession(new StubSession(false));
        MCPServerConnection broken = connectionWithSession(new StubSession(true));

        List<MCPAdapters.MCPServerHealth> report = MCPAdapters.healthReport(
                Map.of("ok", ok, "broken", broken),
                1
        );

        assertEquals(2, report.size());
        MCPAdapters.MCPServerHealth brokenHealth = report.stream()
                .filter(h -> "broken".equals(h.name()))
                .findFirst()
                .orElseThrow();
        MCPAdapters.MCPServerHealth okHealth = report.stream()
                .filter(h -> "ok".equals(h.name()))
                .findFirst()
                .orElseThrow();

        assertEquals("ok", okHealth.status());
        assertEquals(1, okHealth.toolCount());
        assertEquals("error", brokenHealth.status());
        assertTrue(brokenHealth.error().contains("boom"), brokenHealth.error());
    }

    private static MCPServerConnection connectionWithSession(MCPClientSession session) {
        return new MCPServerConnection() {
            @Override
            public MCPClientSession getSession() {
                return session;
            }

            @Override
            public void close() {
            }
        };
    }

    private record StubSession(boolean fail) implements MCPClientSession {
        @Override
        public void initialize() {
        }

        @Override
        public MCPToolResult callTool(String toolName, Map<String, Object> arguments) {
            return null;
        }

        @Override
        public MCPResourceResult readResource(String uri) {
            return null;
        }

        @Override
        public MCPPromptResult getPrompt(String promptName, Map<String, Object> arguments) {
            return null;
        }

        @Override
        public List<MCPToolDefinition> listTools() throws Exception {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            MCPToolDefinition tool = new MCPToolDefinition();
            tool.setName("echo");
            return List.of(tool);
        }

        @Override
        public List<MCPResourceDefinition> listResources() {
            return List.of();
        }

        @Override
        public List<MCPPromptDefinition> listPrompts() {
            return List.of();
        }
    }
}
