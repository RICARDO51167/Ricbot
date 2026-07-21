package ricbot.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ricbot.infra.config.Config;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.mcp.fake.FakeMcpStdioServer;
import ricbot.integration.mcp.MCPAdapters;
import ricbot.integration.mcp.MCPServerConnection;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class MCPIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void stdio_toolsList_toolsCall_andEnabledTools() {
        ToolRegistry registry = new ToolRegistry();

        Config.MCPServerConfig cfg = new Config.MCPServerConfig();
        cfg.setType("stdio");
        cfg.setCommand("java");
        cfg.setArgs(List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeMcpStdioServer.class.getName()
        ));
        cfg.setEnabledTools(List.of("*"));
        cfg.setToolTimeout(2);

        Map<String, MCPServerConnection> conns = MCPAdapters.connectMcpServers(Map.of("demo", cfg), registry);

        Object out = registry.execute("mcp_demo_echo", Map.of("text", "hello"));
        assertEquals("hello", String.valueOf(out));

        try {
            conns.values().forEach(c -> {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            });
        } finally {
        }

        ToolRegistry registry2 = new ToolRegistry();
        Config.MCPServerConfig cfg2 = new Config.MCPServerConfig();
        cfg2.setType("stdio");
        cfg2.setCommand("java");
        cfg2.setArgs(List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeMcpStdioServer.class.getName()
        ));
        cfg2.setEnabledTools(List.of("nope"));
        cfg2.setToolTimeout(2);

        Map<String, MCPServerConnection> conns2 = MCPAdapters.connectMcpServers(Map.of("demo", cfg2), registry2);
        conns2.values().forEach(c -> {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        });
    }

    @Test
    void stdio_timeout_returnsTimeoutMessage() {
        ToolRegistry registry = new ToolRegistry();

        Config.MCPServerConfig cfg = new Config.MCPServerConfig();
        cfg.setType("stdio");
        cfg.setCommand("java");
        cfg.setArgs(List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeMcpStdioServer.class.getName()
        ));
        cfg.setEnabledTools(List.of("*"));
        cfg.setToolTimeout(1);

        Map<String, MCPServerConnection> conns = MCPAdapters.connectMcpServers(Map.of("demo", cfg), registry);
        try {
            Object out = registry.execute("mcp_demo_echo", Map.of("text", "hi", "sleep_ms", 1500));
            assertTrue(String.valueOf(out).contains("timed out"), String.valueOf(out));
        } finally {
            conns.values().forEach(c -> {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            });
        }
    }


    @Test
    void streamableHttp_toolsList_toolsCall_andInferredTransport() throws Exception {
        FakeStreamableHttpMcpServer server = new FakeStreamableHttpMcpServer();
        server.start();

        try {
            ToolRegistry registry = new ToolRegistry();
            Config.MCPServerConfig cfg = new Config.MCPServerConfig();
            cfg.setType("");
            cfg.setUrl(server.rpcUrl());
            cfg.setEnabledTools(List.of("*"));
            cfg.setToolTimeout(1);

            Map<String, MCPServerConnection> conns = MCPAdapters.connectMcpServers(Map.of("demo", cfg), registry);

            Object out = registry.execute("mcp_demo_echo", Map.of("text", "hello"));
            assertEquals("hello", String.valueOf(out));

            conns.values().forEach(c -> {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            });
        } finally {
            server.stop();
        }
    }


    private static Map<String, Object> handleCall(Map<String, Object> params) {
        Map<String, Object> arguments = asObjectMap(params.get("arguments"));
        return Map.of("content", List.of(String.valueOf(arguments.getOrDefault("text", ""))));
    }

    private static Map<String, Object> asObjectMap(Object value) {
        return ricbot.infra.common.JsonMapUtils.asObjectMap(value);
    }

    private static class FakeStreamableHttpMcpServer {
        private HttpServer server;
        private int port;

        void start() throws Exception {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (SocketException e) {
                Assumptions.assumeTrue(false, "当前环境不允许绑定本地端口: " + e.getMessage());
                return;
            }
            server.setExecutor(Executors.newFixedThreadPool(2));

            server.createContext("/mcp", exchange -> {
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    Map<String, Object> req = MAPPER.readValue(body, new TypeReference<>() {});

                    Object id = req.get("id");
                    if (id == null) {
                        exchange.sendResponseHeaders(202, -1);
                        exchange.close();
                        return;
                    }

                    String method = String.valueOf(req.get("method"));
                    Map<String, Object> params = asObjectMap(req.get("params"));

                    Map<String, Object> result = switch (method) {
                        case "initialize" -> Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of());
                        case "tools/list" -> Map.of("tools", List.of(Map.of(
                                "name", "echo",
                                "description", "Echo tool",
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "properties", Map.of("text", Map.of("type", "string")),
                                        "required", List.of("text")
                                )
                        )));
                        case "tools/call" -> handleCall(params);
                        case "resources/list" -> Map.of("resources", List.of());
                        case "prompts/list" -> Map.of("prompts", List.of());
                        default -> Map.of();
                    };

                    byte[] response = MAPPER.writeValueAsBytes(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", result
                    ));
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                } catch (Exception ignored) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });

            server.start();
            port = server.getAddress().getPort();
        }

        String rpcUrl() {
            return URI.create("http://127.0.0.1:" + port + "/mcp").toString();
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }
    }
}
