package ricbot.domain.config;

import java.math.BigDecimal;
import java.time.Instant;

/** Versioned declarative model capability and pricing card. */
public record ModelCard(int schemaVersion, String id, String version, String provider,
                        String modelPattern, String apiMode, Capabilities capabilities,
                        Limits limits, Pricing pricing, String source, Instant effectiveAt) {
    public ModelCard {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported model card schema: " + schemaVersion);
        id = required(id, "id"); version = required(version, "version"); provider = required(provider, "provider");
        modelPattern = required(modelPattern, "modelPattern");
        apiMode = apiMode != null && !apiMode.isBlank() ? apiMode : "UNKNOWN";
        capabilities = capabilities != null ? capabilities : Capabilities.conservative();
        limits = limits != null ? limits : new Limits(-1, -1);
        source = source != null ? source : "";
        effectiveAt = effectiveAt != null ? effectiveAt : Instant.EPOCH;
    }
    public ProviderCapability capability(String model) {
        return new ProviderCapability(provider, new ModelCapability(model,
                bool(capabilities.toolCalling), bool(capabilities.streaming), bool(capabilities.vision),
                bool(capabilities.jsonMode), bool(capabilities.reasoningEffort), limits.contextWindowTokens,
                limits.maxOutputTokens, apiMode), ProviderCapability.SOURCE_MODEL_CARD);
    }
    private static String bool(Boolean value) { return value == null ? "UNKNOWN" : String.valueOf(value); }
    private static String required(String value, String field) {
        String result = value != null ? value.trim() : "";
        if (result.isBlank()) throw new IllegalArgumentException(field + " is required");
        return result;
    }
    public record Capabilities(Boolean toolCalling, Boolean streaming, Boolean vision,
                               Boolean jsonMode, Boolean reasoningEffort) {
        public static Capabilities conservative() { return new Capabilities(false, false, false, false, false); }
    }
    public record Limits(int contextWindowTokens, int maxOutputTokens) { }
    public record Pricing(String currency, long unitTokens, BigDecimal inputUsd,
                          BigDecimal outputUsd, BigDecimal cachedInputUsd) {
        public Pricing { currency = currency != null ? currency : "USD"; unitTokens = unitTokens > 0 ? unitTokens : 1_000_000; }
        public boolean known() { return inputUsd != null && outputUsd != null; }
    }
}
