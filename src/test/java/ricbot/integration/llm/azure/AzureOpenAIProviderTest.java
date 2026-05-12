package ricbot.integration.llm.azure;

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

class AzureOpenAIProviderTest {

    @Test
    void chat_handlesInvalidToolArgsAndStringUsage() throws Exception {
        String body = """
                {
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "lookup",
                              "arguments": "not-json"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": "8",
                    "completion_tokens": 4
                  }
                }
                """;

        HttpServer server = jsonServer(body);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AzureOpenAIProvider provider = new AzureOpenAIProvider("key", base, "deployment");

            LLMResponse response = provider.chat(
                    List.of(Map.of("role", "user", "content", "ping")),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null
            );

            assertEquals("", response.getContent());
            assertEquals("tool_calls", response.getFinishReason());
            assertEquals(8, response.getUsage().get("prompt_tokens"));
            assertEquals(4, response.getUsage().get("completion_tokens"));
            assertEquals(1, response.getToolCalls().size());
            ToolCallRequest call = response.getToolCalls().get(0);
            assertEquals("call_1", call.getId());
            assertEquals("lookup", call.getName());
            assertTrue(call.getArguments().isEmpty());
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
