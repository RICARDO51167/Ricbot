package ricbot.integration.llm.anthropic;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicProviderTest {

    @Test
    void chat_handlesToolUseAndNumericStringUsage() throws Exception {
        String body = """
                {
                  "content": [
                    {"type": "text", "text": "hello"},
                    {
                      "type": "tool_use",
                      "id": "toolu_1",
                      "name": "lookup",
                      "input": {"query": "ricbot"}
                    }
                  ],
                  "stop_reason": "tool_use",
                  "usage": {
                    "input_tokens": "9",
                    "output_tokens": 5
                  }
                }
                """;

        HttpServer server = jsonServer(body);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
            AnthropicProvider provider = new AnthropicProvider("key", url, "claude-test", Map.of());

            LLMResponse response = provider.chat(
                    List.of(Map.of("role", "user", "content", "ping")),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null
            );

            assertEquals("hello", response.getContent());
            assertEquals("tool_use", response.getFinishReason());
            assertEquals(9, response.getUsage().get("prompt_tokens"));
            assertEquals(5, response.getUsage().get("completion_tokens"));
            assertEquals(1, response.getToolCalls().size());
            ToolCallRequest call = response.getToolCalls().get(0);
            assertEquals("toolu_1", call.getId());
            assertEquals("lookup", call.getName());
            assertEquals("ricbot", call.getArguments().get("query"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer jsonServer(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().close();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        return server;
    }
}
