package ricbot.domain.eval;

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

public final class EvalFakeMcpStdioServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalFakeMcpStdioServer() {
    }

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> request;
            try {
                request = MAPPER.readValue(line, new TypeReference<>() {
                });
            } catch (Exception ignored) {
                continue;
            }
            if (!request.containsKey("id")) {
                continue;
            }

            Object id = request.get("id");
            String method = String.valueOf(request.get("method"));
            Map<String, Object> params = ricbot.infra.common.JsonMapUtils.asObjectMap(request.get("params"));
            Map<String, Object> result = switch (method) {
                case "initialize" -> Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of());
                case "tools/list" -> Map.of("tools", List.of(Map.of(
                        "name", "echo",
                        "description", "Echo text for eval harness MCP scenarios.",
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
                case "resources/list", "prompts/list" -> Map.of(method.startsWith("resources") ? "resources" : "prompts", List.of());
                case "resources/read" -> Map.of("contents", List.of());
                case "prompts/get" -> Map.of("messages", List.of());
                default -> Map.of();
            };

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            writer.write(MAPPER.writeValueAsString(response));
            writer.newLine();
            writer.flush();
        }
    }

    private static Map<String, Object> handleToolCall(Map<String, Object> params) throws Exception {
        Map<String, Object> arguments = ricbot.infra.common.JsonMapUtils.asObjectMap(params.get("arguments"));
        Object sleepMs = arguments.get("sleep_ms");
        if (sleepMs instanceof Number n && n.longValue() > 0) {
            Thread.sleep(n.longValue());
        }
        return Map.of("content", List.of(String.valueOf(arguments.getOrDefault("text", ""))));
    }
}
