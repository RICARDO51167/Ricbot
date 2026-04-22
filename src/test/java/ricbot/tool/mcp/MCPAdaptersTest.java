package ricbot.tool.mcp;

import org.junit.jupiter.api.Test;
import ricbot.infra.config.Config;
import ricbot.integration.mcp.MCPAdapters;

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
}
