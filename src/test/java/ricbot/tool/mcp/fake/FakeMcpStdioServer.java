package ricbot.tool.mcp.fake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeMcpStdioServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            Map<String, Object> req;
            try {
                req = MAPPER.readValue(line, new TypeReference<>() {});
            } catch (Exception e) {
                continue;
            }

            if (!req.containsKey("id")) {
                continue;
            }

            Object id = req.get("id");
            String method = String.valueOf(req.get("method"));
            Map<String, Object> params = req.get("params") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m)
                    : new LinkedHashMap<>();

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
                case "tools/call" -> handleToolCall(params);
                case "resources/list" -> Map.of("resources", List.of());
                case "resources/read" -> Map.of("contents", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                case "prompts/get" -> Map.of("messages", List.of());
                default -> Map.of();
            };

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.put("result", result);

            writer.write(MAPPER.writeValueAsString(resp));
            writer.newLine();
            writer.flush();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handleToolCall(Map<String, Object> params) throws Exception {
        Object argsObj = params.get("arguments");
        Map<String, Object> args = argsObj instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();

        Object sleep = args.get("sleep_ms");
        if (sleep instanceof Number n && n.longValue() > 0) {
            Thread.sleep(n.longValue());
        }

        String text = String.valueOf(args.getOrDefault("text", ""));
        return Map.of("content", List.of(text));
    }
}

