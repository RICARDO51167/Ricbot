package ricbot.integration.llm.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.OpenAIResponsesSupport;
import ricbot.integration.llm.api.ToolCallRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Azure OpenAI Provider implementation.
 */
public class AzureOpenAIProvider extends LLMProvider {

    private final HttpClient client;
    private final String apiVersion;

    public AzureOpenAIProvider(String apiKey, String apiBase, String defaultModel) {
        this(apiKey, apiBase, defaultModel, "2024-02-15-preview");
    }

    public AzureOpenAIProvider(String apiKey, String apiBase, String defaultModel, String apiVersion) {
        super(apiKey, apiBase);
        this.defaultModel = defaultModel != null ? defaultModel : "gpt-4";
        this.apiVersion = apiVersion;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public LLMResponse chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice
    ) throws Exception {
        String deployment = (model != null && !model.isBlank()) ? model : defaultModel;
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", sanitizeEmptyContent(messages));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }
        body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());
        body.put("temperature", temperature != null ? temperature : generation.getTemperature());

        String json = MAPPER.writeValueAsString(body);
        
        String baseUrl = apiBase.endsWith("/") ? apiBase : apiBase + "/";
        String url = baseUrl + "openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return new LLMResponse()
                    .setFinishReason("error")
                    .setContent("Azure OpenAI 错误: " + response.statusCode() + " " + response.body());
        }

        return parseAzureResponse(response.body());
    }

    @SuppressWarnings("unchecked")
    private LLMResponse parseAzureResponse(String json) throws Exception {
        Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
        if (choices == null || choices.isEmpty()) {
            return new LLMResponse().setFinishReason("error").setContent("Azure OpenAI 返回的选择为空");
        }

        Map<String, Object> first = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) first.get("message");
        String content = (String) message.get("content");
        String finishReason = (String) first.get("finish_reason");

        List<ToolCallRequest> toolCalls = new ArrayList<>();
        List<Map<String, Object>> tcRaw = (List<Map<String, Object>>) message.get("tool_calls");
        if (tcRaw != null) {
            for (Map<String, Object> tc : tcRaw) {
                String id = (String) tc.get("id");
                Map<String, Object> func = (Map<String, Object>) tc.get("function");
                String name = (String) func.get("name");
                String argsJson = (String) func.get("arguments");
                Map<String, Object> args = MAPPER.readValue(argsJson, new TypeReference<>() {});
                toolCalls.add(new ToolCallRequest(id, name, args));
            }
        }

        Map<String, Object> usageRaw = (Map<String, Object>) map.get("usage");
        Map<String, Integer> usage = new HashMap<>();
        if (usageRaw != null) {
            usage.put("prompt_tokens", (Integer) usageRaw.get("prompt_tokens"));
            usage.put("completion_tokens", (Integer) usageRaw.get("completion_tokens"));
        }

        return new LLMResponse()
                .setContent(content)
                .setToolCalls(toolCalls)
                .setFinishReason(finishReason)
                .setUsage(usage);
    }

    @Override
    public LLMResponse chatStream(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice,
            StreamDeltaHandler onDelta,
            StreamEndHandler onEnd
    ) throws Exception {
        String deployment = (model != null && !model.isBlank()) ? model : defaultModel;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", sanitizeEmptyContent(messages));
        body.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }
        body.put("max_tokens", maxTokens != null ? maxTokens : generation.getMaxTokens());
        body.put("temperature", temperature != null ? temperature : generation.getTemperature());

        String json = MAPPER.writeValueAsString(body);

        String baseUrl = apiBase.endsWith("/") ? apiBase : apiBase + "/";
        String url = baseUrl + "openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Azure OpenAI 流式请求错误: " + response.statusCode());
        }

        return OpenAIResponsesSupport.consumeSSE(response.body(), onDelta, onEnd);
    }
}