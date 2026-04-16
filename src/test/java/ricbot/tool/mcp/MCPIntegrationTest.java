package ricbot.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import ricbot.infra.config.Config;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.mcp.fake.FakeMcpStdioServer;
import ricbot.integration.mcp.MCPAdapters;
import ricbot.integration.mcp.MCPServerConnection;

import java.io.OutputStream;
import java.net.InetSocketAddress;
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
    void sse_toolsCall_andTimeout() throws Exception {
        FakeSseMcpServer server = new FakeSseMcpServer();
        server.start();

        try {
            ToolRegistry registry = new ToolRegistry();
            Config.MCPServerConfig cfg = new Config.MCPServerConfig();
            cfg.setType("sse");
            cfg.setUrl(server.sseUrl());
            cfg.setEnabledTools(List.of("*"));
            cfg.setToolTimeout(1);

            Map<String, MCPServerConnection> conns = MCPAdapters.connectMcpServers(Map.of("demo", cfg), registry);

            Object out = registry.execute("mcp_demo_echo", Map.of("text", "hello"));
            assertEquals("hello", String.valueOf(out));

            Object timeout = registry.execute("mcp_demo_echo", Map.of("text", "x", "sleep_ms", 1500));
            assertTrue(String.valueOf(timeout).contains("timed out"), String.valueOf(timeout));

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

    private static class FakeSseMcpServer {
        private HttpServer server;
        private int port;
        private final AtomicReference<OutputStream> sseOut = new AtomicReference<>();
        private volatile boolean running;

        void start() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            running = true;
            server.setExecutor(Executors.newCachedThreadPool());

            server.createContext("/sse", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                OutputStream os = exchange.getResponseBody();
                sseOut.set(os);

                int p = exchange.getLocalAddress().getPort();
                String endpoint = "http://127.0.0.1:" + p + "/rpc";
                os.write(("event: endpoint\n").getBytes(StandardCharsets.UTF_8));
                os.write(("data: " + endpoint + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();

                while (running) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });

            server.createContext("/rpc", exchange -> {
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    Map<String, Object> req = MAPPER.readValue(body, new TypeReference<>() {});

                    Object id = req.get("id");
                    String method = String.valueOf(req.get("method"));
                    Map<String, Object> params = req.get("params") instanceof Map<?, ?> m
                            ? (Map<String, Object>) m
                            : Map.of();

                    Map<String, Object> result = switch (method) {
                        case "initialize" -> Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of());
                        case "tools/list" -> Map.of("tools", List.of(Map.of(
                                "name", "echo",
                                "description", "Echo tool",
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "text", Map.of("type", "string"),
                                                "sleep_ms", Map.of("type", "integer")
                                        ),
                                        "required", List.of("text")
                                )
                        )));
                        case "tools/call" -> handleCall(params);
                        case "resources/list" -> Map.of("resources", List.of());
                        case "prompts/list" -> Map.of("prompts", List.of());
                        case "resources/read" -> Map.of("contents", List.of());
                        case "prompts/get" -> Map.of("messages", List.of());
                        default -> Map.of();
                    };

                    Map<String, Object> resp = Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", result
                    );

                    OutputStream os = sseOut.get();
                    if (os != null) {
                        os.write(("data: " + MAPPER.writeValueAsString(resp) + "\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }

                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                } catch (Exception ignored) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });

            server.start();
            port = server.getAddress().getPort();
        }

        String sseUrl() {
            return URI.create("http://127.0.0.1:" + port + "/sse").toString();
        }

        void stop() {
            running = false;
            if (server != null) {
                server.stop(0);
            }
            OutputStream os = sseOut.get();
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> handleCall(Map<String, Object> params) {
            Object argsObj = params.get("arguments");
            Map<String, Object> args = argsObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Object sleep = args.get("sleep_ms");
            if (sleep instanceof Number n && n.longValue() > 0) {
                try {
                    Thread.sleep(n.longValue());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Map.of("content", List.of(String.valueOf(args.getOrDefault("text", ""))));
        }
    }
}
