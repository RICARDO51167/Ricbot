package ricbot.integration.mcp;

import org.junit.jupiter.api.Test;
import ricbot.infra.config.Config;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.mcp.fake.FakeMcpStdioServer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MCPDiagnosticServiceTest {

    @Test
    void diagnostics_handlesNoMcpConfig() {
        ToolRegistry registry = new ToolRegistry();
        MCPLoader loader = new MCPLoader(registry, Map.of());

        Map<String, Object> diagnostics = new MCPDiagnosticService(registry, loader, new Config()).diagnostics();

        assertEquals(0, diagnostics.get("configuredCount"));
        assertEquals(List.of(), diagnostics.get("servers"));
        assertNotNull(diagnostics.get("schemaHash"));
    }

    @Test
    void diagnostics_reportsLoadedServerAndStableSchemaHash() {
        ToolRegistry registry = new ToolRegistry();
        Config.MCPServerConfig cfg = fakeServerConfig(List.of("*"));
        MCPLoader loader = new MCPLoader(registry, Map.of("demo", cfg), true);
        loader.load();
        try {
            Map<String, Object> first = new MCPDiagnosticService(registry, loader, new Config()).diagnostics();
            Map<String, Object> second = new MCPDiagnosticService(registry, loader, new Config()).diagnostics();

            assertEquals(1L, first.get("connectedCount"));
            Map<?, ?> server = firstServer(first);
            assertEquals("demo", server.get("name"));
            assertEquals("CONNECTED", server.get("status"));
            assertEquals(List.of("mcp_demo_echo"), server.get("registeredToolNames"));
            assertEquals(first.get("schemaHash"), second.get("schemaHash"));
            assertTrue(String.valueOf(first.get("schemaHash")).matches("[0-9a-f]{64}"));
        } finally {
            loader.close();
        }
    }

    @Test
    void diagnostics_reportsFailedServerAndRedactsSensitiveConfig() {
        ToolRegistry registry = new ToolRegistry();
        Config.MCPServerConfig cfg = new Config.MCPServerConfig();
        cfg.setType("stdio");
        cfg.setCommand("missing-token-command");
        cfg.setArgs(List.of("--api_key=secret-value"));
        cfg.setEnv(Map.of("API_TOKEN", "secret-env-token", "PLAIN_ENV", "visible"));
        MCPLoader loader = new MCPLoader(registry, Map.of("bad", cfg), true);
        loader.load();

        Map<String, Object> diagnostics = new MCPDiagnosticService(registry, loader, new Config()).diagnostics();
        Map<?, ?> server = firstServer(diagnostics);

        assertEquals("FAILED", server.get("status"));
        assertEquals("[REDACTED]", server.get("lastError"));
        String text = diagnostics.toString();
        assertTrue(text.contains("[REDACTED]"), text);
        assertTrue(text.contains("[SET]"), text);
        assertFalse(text.contains("secret-env-token"), text);
        assertFalse(text.contains("secret-value"), text);
        assertFalse(text.contains("visible"), text);
    }

    @Test
    void diagnostics_explainsEnabledToolsFiltering() {
        ToolRegistry registry = new ToolRegistry();
        Config.MCPServerConfig cfg = fakeServerConfig(List.of("nope"));
        MCPLoader loader = new MCPLoader(registry, Map.of("demo", cfg), true);
        loader.load();
        try {
            Map<String, Object> diagnostics = new MCPDiagnosticService(registry, loader, new Config()).diagnostics();
            Map<?, ?> server = firstServer(diagnostics);
            Map<?, ?> tool = firstTool(diagnostics);

            assertEquals("CONNECTED", server.get("status"));
            assertEquals(List.of(), server.get("registeredToolNames"));
            assertEquals(List.of("mcp_demo_echo"), server.get("filteredToolNames"));
            assertEquals(false, tool.get("registeredToToolRegistry"));
            assertEquals(false, tool.get("allowedByEnabledTools"));
            assertEquals(false, tool.get("exposedToModel"));
            assertEquals("filtered by enabled_tools", tool.get("reason"));
        } finally {
            loader.close();
        }
    }

    private static Config.MCPServerConfig fakeServerConfig(List<String> enabledTools) {
        Config.MCPServerConfig cfg = new Config.MCPServerConfig();
        cfg.setType("stdio");
        cfg.setCommand("java");
        cfg.setArgs(List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeMcpStdioServer.class.getName()
        ));
        cfg.setEnabledTools(enabledTools);
        cfg.setToolTimeout(2);
        return cfg;
    }

    private static Map<?, ?> firstServer(Map<String, Object> diagnostics) {
        List<?> servers = (List<?>) diagnostics.get("servers");
        assertFalse(servers.isEmpty());
        return (Map<?, ?>) servers.get(0);
    }

    private static Map<?, ?> firstTool(Map<String, Object> diagnostics) {
        List<?> tools = (List<?>) diagnostics.get("tools");
        assertFalse(tools.isEmpty());
        return (Map<?, ?>) tools.get(0);
    }
}
