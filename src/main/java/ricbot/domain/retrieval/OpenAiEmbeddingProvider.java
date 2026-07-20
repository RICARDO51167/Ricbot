package ricbot.domain.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** OpenAI-compatible embeddings client using the JDK HTTP stack. */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiEmbeddingProvider(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, Duration.ofSeconds(30), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
    }

    OpenAiEmbeddingProvider(String baseUrl, String apiKey, String model, Duration timeout, HttpClient client) {
        String base = required(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.endpoint = URI.create(base.endsWith("/v1") ? base + "/embeddings" : base + "/v1/embeddings");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !isLoopback(endpoint.getHost())) {
            throw new IllegalArgumentException("embedding endpoint must use HTTPS unless it is loopback");
        }
        this.apiKey = required(apiKey, "apiKey");
        this.model = required(model, "model");
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.client = java.util.Objects.requireNonNull(client, "client");
    }

    @Override
    public String modelId() {
        return "openai:" + model;
    }

    @Override
    public double[] embed(String text) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(Map.of("model", model, "input", text != null ? text : ""));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("embedding request failed with HTTP " + response.statusCode());
            }
            JsonNode vector = MAPPER.readTree(response.body()).path("data").path(0).path("embedding");
            if (!vector.isArray() || vector.isEmpty()) throw new IllegalStateException("embedding response has no vector");
            double[] result = new double[vector.size()];
            for (int i = 0; i < result.length; i++) result[i] = vector.get(i).asDouble();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding request interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("embedding request failed", e);
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
