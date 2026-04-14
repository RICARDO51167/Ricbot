package ricbot.llm.api;

import ricbot.llm.openai.OpenAIResponsesSupport;

import java.util.*;

/**
 * 对应 Python: AzureOpenAIProvider
 *
 * 主要目标：
 * 1. 适配 Azure OpenAI Responses API
 * 2. 构建请求 body
 * 3. 兼容 reasoning / tools / tool_choice
 */
public class AzureOpenAIProvider extends LLMProvider {

    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();

    /**
     * 这里你后面可以替换成真正 SDK client
     */
    private Object client;

    public AzureOpenAIProvider(String apiKey, String apiBase, String defaultModel) {
        super(apiKey, apiBase);
        this.defaultModel = defaultModel != null ? defaultModel : "gpt-5.2-chat";

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Azure OpenAI api_key is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            throw new IllegalArgumentException("Azure OpenAI api_base is required");
        }

        if (!apiBase.endsWith("/")) {
            apiBase += "/";
        }
        this.apiBase = apiBase;
        this.defaultHeaders.put("x-session-affinity", UUID.randomUUID().toString().replace("-", ""));

        // TODO:
        // 这里后面替换成真正 Azure/OpenAI Java SDK client
        this.client = new Object();
    }

    /**
     * 对应 Python: _supports_temperature(...)
     */
    public static boolean supportsTemperature(String deploymentName, String reasoningEffort) {
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            return false;
        }
        String name = deploymentName != null ? deploymentName.toLowerCase(Locale.ROOT) : "";
        return !(name.contains("gpt-5") || name.contains("o1") || name.contains("o3") || name.contains("o4"));
    }

    /**
     * 对应 Python: _build_body(...)
     */
    public Map<String, Object> buildBody(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice
    ) {
        String deployment = (model != null && !model.isBlank()) ? model : defaultModel;

        // TODO:
        // convert_messages(...) 你后面可以单独抽一个 OpenAIResponsesSupport.java
        Map<String, Object> converted = OpenAIResponsesSupport.convertMessages(sanitizeEmptyContent(messages));
        Object instructions = converted.get("instructions");
        Object inputItems = converted.get("input");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deployment);
        body.put("instructions", instructions != null ? instructions : null);
        body.put("input", inputItems);
        body.put("max_output_tokens", Math.max(1, maxTokens));
        body.put("store", false);
        body.put("stream", false);

        if (supportsTemperature(deployment, reasoningEffort)) {
            body.put("temperature", temperature);
        }

        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.put("reasoning", Map.of("effort", reasoningEffort));
            body.put("include", List.of("reasoning.encrypted_content"));
        }

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", OpenAIResponsesSupport.convertTools(tools));
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
        }

        return body;
    }

    /**
     * 对应 Python: _handle_error(e)
     */
    public static LLMResponse handleError(Exception e) {
        String msg = "Error calling Azure OpenAI: " + e.getMessage();
        Double retryAfter = extractRetryAfter(msg);

        return new LLMResponse()
                .setContent(msg)
                .setFinishReason("error")
                .setRetryAfter(retryAfter);
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
    ) {
        int finalMaxTokens = maxTokens != null ? maxTokens : generation.getMaxTokens();
        double finalTemperature = temperature != null ? temperature : generation.getTemperature();
        String finalReasoning = reasoningEffort != null ? reasoningEffort : generation.getReasoningEffort();

        Map<String, Object> body = buildBody(
                messages,
                tools,
                model,
                finalMaxTokens,
                finalTemperature,
                finalReasoning,
                toolChoice
        );

        try {
            // TODO:
            // 这里后续替换成真正 SDK:
            // response = client.responses.create(**body)
            Object rawResponse = AzureSdkShim.responsesCreate(client, body);
            return OpenAIResponsesSupport.parseResponseOutput(rawResponse);
        } catch (Exception e) {
            return handleError(e);
        }
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
    ) {
        int finalMaxTokens = maxTokens != null ? maxTokens : generation.getMaxTokens();
        double finalTemperature = temperature != null ? temperature : generation.getTemperature();
        String finalReasoning = reasoningEffort != null ? reasoningEffort : generation.getReasoningEffort();

        Map<String, Object> body = buildBody(
                messages,
                tools,
                model,
                finalMaxTokens,
                finalTemperature,
                finalReasoning,
                toolChoice
        );
        body.put("stream", true);

        try {
            Object stream = AzureSdkShim.responsesStream(client, body);
            return OpenAIResponsesSupport.consumeSdkStream(stream, onDelta, onEnd);
        } catch (Exception e) {
            return handleError(e);
        }
    }
}