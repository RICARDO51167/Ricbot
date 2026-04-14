package ricbot.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.api.ToolRegistry;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) 加载器。
 * 
 * 允许通过标准 I/O 运行 MCP 服务器并将其工具注册到 ToolRegistry。
 */
public class MCPLoader {
    private static final Logger log = LoggerFactory.getLogger(MCPLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry registry;
    private final Map<String, Object> serverConfigs;

    public MCPLoader(ToolRegistry registry, Map<String, Object> serverConfigs) {
        this.registry = registry;
        this.serverConfigs = serverConfigs != null ? serverConfigs : Collections.emptyMap();
    }

    public void load() {
        for (Map.Entry<String, Object> entry : serverConfigs.entrySet()) {
            String serverName = entry.getKey();
            Object config = entry.getValue();
            if (!(config instanceof Map<?, ?> cfg)) continue;

            try {
                registerServerTools(serverName, cfg);
            } catch (Exception e) {
                log.error("Failed to register MCP server tools for {}: {}", serverName, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void registerServerTools(String serverName, Map<?, ?> cfg) throws Exception {
        Object cmdObj = cfg.get("command");
        if (!(cmdObj instanceof String command)) return;

        List<String> args = new ArrayList<>();
        Object argsObj = cfg.get("args");
        if (argsObj instanceof List<?> list) {
            for (Object o : list) args.add(String.valueOf(o));
        }

        Map<String, String> env = new HashMap<>();
        Object envObj = cfg.get("env");
        if (envObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                env.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }

        MCPClient client = new MCPClient(command, args, env);
        List<Map<String, Object>> tools = client.listTools();

        for (Map<String, Object> toolDef : tools) {
            String name = (String) toolDef.get("name");
            String description = (String) toolDef.get("description");
            Map<String, Object> inputSchema = (Map<String, Object>) toolDef.get("inputSchema");

            registry.register(new MCPWrappedTool(serverName, name, description, inputSchema, client));
        }
        
        log.info("Registered {} tools from MCP server {}", tools.size(), serverName);
    }

    private static class MCPWrappedTool extends Tool {
        private final String serverName;
        private final String name;
        private final String description;
        private final Map<String, Object> inputSchema;
        private final MCPClient client;

        public MCPWrappedTool(String serverName, String name, String description, Map<String, Object> inputSchema, MCPClient client) {
            this.serverName = serverName;
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.client = client;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<ToolParam> getParams() {
            List<ToolParam> params = new ArrayList<>();
            if (inputSchema != null) {
                Object properties = inputSchema.get("properties");
                if (properties instanceof Map<?, ?> props) {
                    List<String> requiredList = new ArrayList<>();
                    Object req = inputSchema.get("required");
                    if (req instanceof List<?> list) {
                        for (Object o : list) requiredList.add(String.valueOf(o));
                    }

                    for (Map.Entry<?, ?> e : props.entrySet()) {
                        String pName = String.valueOf(e.getKey());
                        Map<String, Object> pDef = (Map<String, Object>) e.getValue();
                        String type = (String) pDef.get("type");
                        String desc = (String) pDef.get("description");
                        boolean required = requiredList.contains(pName);
                        params.add(new ToolParam(pName, type, desc, required));
                    }
                }
            }
            return params;
        }

        @Override
        public String execute(Map<String, Object> params) throws Exception {
            return client.callTool(name, params);
        }
    }

    /**
     * 简化的 MCP 客户端，通过 StdIO 与进程通信。
     */
    private static class MCPClient {
        private final String command;
        private final List<String> args;
        private final Map<String, String> env;
        private Process process;
        private BufferedWriter writer;
        private BufferedReader reader;
        private final AtomicLong idGen = new AtomicLong(1);

        public MCPClient(String command, List<String> args, Map<String, String> env) {
            this.command = command;
            this.args = args;
            this.env = env;
        }

        private synchronized void ensureProcess() throws IOException {
            if (process != null && process.isAlive()) return;

            List<String> fullCmd = new ArrayList<>();
            fullCmd.add(command);
            fullCmd.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.environment().putAll(env);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            
            this.process = pb.start();
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            
            // MCP 初始化握手
            initialize();
        }

        private void initialize() throws IOException {
            Map<String, Object> params = new HashMap<>();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", Collections.emptyMap());
            params.put("clientInfo", Map.of("name", "ricbot-java", "version", "0.1.0"));

            call("initialize", params);
            sendNotification("notifications/initialized", Collections.emptyMap());
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> listTools() throws IOException {
            Map<String, Object> result = call("tools/list", Collections.emptyMap());
            return (List<Map<String, Object>>) result.get("tools");
        }

        public String callTool(String name, Map<String, Object> arguments) throws IOException {
            Map<String, Object> params = new HashMap<>();
            params.put("name", name);
            params.put("arguments", arguments);

            Map<String, Object> result = call("tools/call", params);
            List<?> content = (List<?>) result.get("content");
            if (content == null || content.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (Object item : content) {
                if (item instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                    sb.append(m.get("text"));
                }
            }
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private synchronized Map<String, Object> call(String method, Map<String, Object> params) throws IOException {
            ensureProcess();
            long id = idGen.getAndIncrement();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params);

            writer.write(MAPPER.writeValueAsString(request));
            writer.newLine();
            writer.flush();

            String line = reader.readLine();
            if (line == null) throw new IOException("MCP server closed connection");

            Map<String, Object> response = MAPPER.readValue(line, new TypeReference<>() {});
            if (response.containsKey("error")) {
                throw new IOException("MCP Error: " + response.get("error"));
            }
            return (Map<String, Object>) response.get("result");
        }

        private synchronized void sendNotification(String method, Map<String, Object> params) throws IOException {
            ensureProcess();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("method", method);
            request.put("params", params);

            writer.write(MAPPER.writeValueAsString(request));
            writer.newLine();
            writer.flush();
        }
    }
}
