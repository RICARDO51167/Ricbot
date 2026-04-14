package ricbot.llm.github;


import ricbot.llm.api.GitHubCopilotAuth;
import ricbot.llm.api.LLMResponse;
import ricbot.llm.api.OpenAICompatProvider;
import ricbot.llm.api.ProviderRegistry;

/**
 * 对应 Python: GitHubCopilotProvider
 *
 * 主要目标：
 * 1. 先用 GitHub OAuth token 换取 Copilot access token
 * 2. 再把 token 注入到 OpenAICompatProvider
 */
public class GitHubCopilotProvider extends OpenAICompatProvider {

    private String copilotAccessToken;
    private double copilotExpiresAt = 0.0;

    public GitHubCopilotProvider(String defaultModel) {
        super(
                "no-key",
                ricbot.llm.api.GitHubCopilotAuth.DEFAULT_COPILOT_BASE_URL,
                defaultModel != null ? defaultModel : "github-copilot/gpt-4.1",
                null,
                ProviderRegistry.findByName("github_copilot")
        );
    }

    /**
     * 对应 Python: _get_copilot_access_token()
     */
    protected String getCopilotAccessToken() throws Exception {
        double now = System.currentTimeMillis() / 1000.0;

        if (copilotAccessToken != null && now < copilotExpiresAt - ricbot.llm.api.GitHubCopilotAuth.EXPIRY_SKEW_SECONDS) {
            return copilotAccessToken;
        }

        ricbot.llm.api.GitHubCopilotAuth.OAuthToken githubToken = ricbot.llm.api.GitHubCopilotAuth.loadGithubToken();
        if (githubToken == null || githubToken.getAccess() == null || githubToken.getAccess().isBlank()) {
            throw new RuntimeException("GitHub Copilot is not logged in. Run: nanobot provider login github-copilot");
        }

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();

        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(ricbot.llm.api.GitHubCopilotAuth.DEFAULT_COPILOT_TOKEN_URL))
                .timeout(java.time.Duration.ofSeconds(20))
                .GET();

        for (var e : GitHubCopilotAuth.copilotHeaders(githubToken.getAccess()).entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }

        java.net.http.HttpResponse<String> response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("GitHub Copilot token exchange failed: " + response.body());
        }

        java.util.Map<String, Object> payload =
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        response.body(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {}
                );

        Object tokenObj = payload.get("token");
        if (tokenObj == null || String.valueOf(tokenObj).isBlank()) {
            throw new RuntimeException("GitHub Copilot token exchange returned no token.");
        }

        Object expiresAtObj = payload.get("expires_at");
        if (expiresAtObj instanceof Number n) {
            copilotExpiresAt = n.doubleValue();
        } else {
            int refreshIn = payload.get("refresh_in") instanceof Number n ? n.intValue() : 1500;
            copilotExpiresAt = (System.currentTimeMillis() / 1000.0) + refreshIn;
        }

        copilotAccessToken = String.valueOf(tokenObj);
        return copilotAccessToken;
    }

    /**
     * 对应 Python: _refresh_client_api_key()
     */
    protected String refreshClientApiKey() throws Exception {
        String token = getCopilotAccessToken();
        this.apiKey = token;
        return token;
    }

    @Override
    public LLMResponse chat(
            java.util.List<java.util.Map<String, Object>> messages,
            java.util.List<java.util.Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice
    ) throws Exception {
        refreshClientApiKey();
        return super.chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice);
    }

    @Override
    public LLMResponse chatStream(
            java.util.List<java.util.Map<String, Object>> messages,
            java.util.List<java.util.Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice,
            ricbot.llm.api.LLMProvider.StreamDeltaHandler onDelta,
            ricbot.llm.api.LLMProvider.StreamEndHandler onEnd
    ) throws Exception {
        refreshClientApiKey();
        return super.chatStream(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice, onDelta, onEnd);
    }
}