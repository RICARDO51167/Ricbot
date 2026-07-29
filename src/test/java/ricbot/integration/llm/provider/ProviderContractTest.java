package ricbot.integration.llm.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import ricbot.infra.config.Config;
import ricbot.integration.llm.anthropic.AnthropicProvider;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.integration.llm.openai.OpenAICompatProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProviderContractTest {

    @Test
    void azureConfigurationUsesTheOpenAiCompatibleAdapter() {
        Config config = new Config();
        config.getAgents().getDefaults().setModel("azure_openai/deployment");
        Config.ProviderConfig azure = config.getProviders().getOrCreate("azure_openai");
        azure.setApiBase("https://example.openai.azure.com/openai/v1");
        azure.setExtraHeaders(Map.of("api-key", "key"));

        assertInstanceOf(OpenAICompatProvider.class, ProviderFactory.makeProvider(config));
        assertEquals("openai_compat", ProviderRegistry.findByName("azure_openai").getBackend());
    }

    @Test
    void supportedProvidersNormalizeTextToolCallsAndUsage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, openAiResponse()));
        server.createContext("/v1/messages", exchange -> respond(exchange, anthropicResponse()));
        server.start();

        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            ProviderSpec custom = new ProviderSpec("custom", List.of(), "", "openai_compat")
                    .setDirect(true);

            assertNormalized(new OpenAICompatProvider("key", root, "test-model", Map.of(), custom));
            assertNormalized(new AnthropicProvider("key", root + "/v1/messages", "test-model", Map.of()));
        } finally {
            server.stop(0);
        }
    }

    private static void assertNormalized(LLMProvider provider) throws Exception {
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
        assertEquals(11, response.getUsage().get("prompt_tokens"));
        assertEquals(7, response.getUsage().get("completion_tokens"));
        assertEquals(1, response.getToolCalls().size());
        ToolCallRequest call = response.getToolCalls().get(0);
        assertEquals("tool_1", call.getId());
        assertEquals("lookup", call.getName());
        assertEquals("ricbot", call.getArguments().get("query"));
    }

    private static String openAiResponse() {
        return """
                {
                  "choices": [{
                    "finish_reason": "tool_calls",
                    "message": {
                      "content": "hello",
                      "tool_calls": [{
                        "id": "tool_1",
                        "type": "function",
                        "function": {"name": "lookup", "arguments": "{\\\"query\\\":\\\"ricbot\\\"}"}
                      }]
                    }
                  }],
                  "usage": {"prompt_tokens": 11, "completion_tokens": 7}
                }
                """;
    }

    private static String anthropicResponse() {
        return """
                {
                  "content": [
                    {"type": "text", "text": "hello"},
                    {"type": "tool_use", "id": "tool_1", "name": "lookup", "input": {"query": "ricbot"}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {"input_tokens": 11, "output_tokens": 7}
                }
                """;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().close();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
