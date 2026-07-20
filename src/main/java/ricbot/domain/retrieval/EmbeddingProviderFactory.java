package ricbot.domain.retrieval;

import java.util.Locale;

/** Environment-driven provider selection with an offline hashing default. */
public final class EmbeddingProviderFactory {
    private EmbeddingProviderFactory() {
    }

    public static EmbeddingProvider fromEnvironment() {
        String provider = value("RICBOT_EMBEDDING_PROVIDER", "hashing").toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "hashing", "local" -> new HashingEmbeddingProvider();
            case "openai", "openai-compatible" -> new OpenAiEmbeddingProvider(
                    value("RICBOT_EMBEDDING_BASE_URL", "https://api.openai.com"),
                    required("RICBOT_EMBEDDING_API_KEY"),
                    value("RICBOT_EMBEDDING_MODEL", "text-embedding-3-small")
            );
            default -> throw new IllegalArgumentException("unsupported embedding provider: " + provider);
        };
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
        return value.trim();
    }

    private static String value(String key, String fallback) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
