package ricbot.llm.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * 对应 Python: OpenAICodexProvider
 *
 * 主要目标：
 * 1. 用 Codex OAuth 调 Responses API
 * 2. 支持普通 chat 与 chat_stream
 */
public class OpenAICodexProvider extends LLMProvider {

    public static final String DEFAULT_CODEX_URL = "https://chatgpt.com/backend-api/codex/responses";
    public static final String DEFAULT_ORIGINATOR = "oldricbot";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public OpenAICodexProvider(String defaultModel) {
        super(null, null);
        this.defaultModel = defaultModel != null ? defaultModel : "openai-codex/gpt-5.1-codex";
    }

    /**
     * 对应 Python: _call_codex(...)
     */
    protected LLMResponse callCodex(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            String reasoningEffort,
            Object toolChoice,
            StreamDeltaHandler onContentDelta
    ) {
        try {
            String finalModel = model != null ? model : defaultModel;
            Map<String, Object> converted = OpenAIResponsesSupport.convertMessages(messages);
            String systemPrompt = (String) converted.get("instructions");
            Object inputItems = converted.get("input");

            // TODO:
            // 这里后面替换成真正 codex oauth token 获取
            CodexToken token = CodexTokenProvider.getCodexToken();

            Map<String, String> headers = buildHeaders(token.accountId(), token.accessToken());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", stripModelPrefix(finalModel));
            body.put("store", false);
            body.put("stream", true);
            body.put("instructions", systemPrompt);
            body.put("input", inputItems);
            body.put("text", Map.of("verbosity", "medium"));
            body.put("include", List.of("reasoning.encrypted_content"));
            body.put("prompt_cache_key", promptCacheKey(messages));
            body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
            body.put("parallel_tool_calls", true);

            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                body.put("reasoning", Map.of("effort", reasoningEffort));
            }
            if (tools != null && !tools.isEmpty()) {
                body.put("tools", OpenAIResponsesSupport.convertTools(tools));
            }

            try {
                CodexRequestResult result = requestCodex(
                        DEFAULT_CODEX_URL, headers, body, true, onContentDelta
                );
                return new LLMResponse()
                        .setContent(result.content())
                        .setToolCalls(result.toolCalls())
                        .setFinishReason(result.finishReason());
            } catch (Exception e) {
                if (e.getMessage() == null || !e.getMessage().contains("CERTIFICATE_VERIFY_FAILED")) {
                    throw e;
                }

                System.err.println("SSL verification failed for Codex API; retrying with verify=false");
                CodexRequestResult result = requestCodex(
                        DEFAULT_CODEX_URL, headers, body, false, onContentDelta
                );
                return new LLMResponse()
                        .setContent(result.content())
                        .setToolCalls(result.toolCalls())
                        .setFinishReason(result.finishReason());
            }

        } catch (Exception e) {
            String msg = "Error calling Codex: " + e.getMessage();
            Double retryAfter = extractRetryAfter(msg);
            return new LLMResponse()
                    .setContent(msg)
                    .setFinishReason("error")
                    .setRetryAfter(retryAfter);
        }
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
        return callCodex(messages, tools, model, reasoningEffort, toolChoice, null);
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
        LLMResponse response = callCodex(messages, tools, model, reasoningEffort, toolChoice, onDelta);
        if (onEnd != null) {
            onEnd.onEnd(false);
        }
        return response;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public static String stripModelPrefix(String model) {
        if (model == null) {
            return null;
        }
        if (model.startsWith("openai-codex/") || model.startsWith("openai_codex/")) {
            return model.substring(model.indexOf('/') + 1);
        }
        return model;
    }

    public static Map<String, String> buildHeaders(String accountId, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("chatgpt-account-id", accountId);
        headers.put("OpenAI-Beta", "responses=experimental");
        headers.put("originator", DEFAULT_ORIGINATOR);
        headers.put("User-Agent", "nanobot (java)");
        headers.put("accept", "text/event-stream");
        headers.put("content-type", "application/json");
        return headers;
    }

    /**
     * 对应 Python: _prompt_cache_key(messages)
     */
    public static String promptCacheKey(List<Map<String, Object>> messages) {
        try {
            String json = MAPPER.writeValueAsString(messages);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /**
     * 对应 Python: _request_codex(...)
     */
    public static CodexRequestResult requestCodex(
            String url,
            Map<String, String> headers,
            Map<String, Object> body,
            boolean verify,
            StreamDeltaHandler onContentDelta
    ) throws Exception {
        String json = MAPPER.writeValueAsString(body);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(json));

        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            String text = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new CodexHttpError("Codex request failed: " + text, extractRetryAfter(text));
        }

        LLMResponse llmResponse = OpenAIResponsesSupport.consumeSse(response.body(), onContentDelta);
        return new CodexRequestResult(
                llmResponse.getContent(),
                llmResponse.getToolCalls(),
                llmResponse.getFinishReason()
        );
    }

    public record CodexRequestResult(
            String content,
            List<ToolCallRequest> toolCalls,
            String finishReason
    ) {
    }

    public record CodexToken(String accountId, String accessToken) {
    }

    public static class CodexHttpError extends RuntimeException {
        private final Double retryAfter;

        public CodexHttpError(String message, Double retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public Double getRetryAfter() {
            return retryAfter;
        }
    }

    /**
     * 占位实现，后面你接 OAuth CLI 或本地 token store 时替换
     */
    public static class CodexTokenProvider {
        public static CodexToken getCodexToken() {
            throw new UnsupportedOperationException("Codex OAuth token provider not implemented yet");
        }
    }
}