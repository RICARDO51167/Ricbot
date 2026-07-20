package ricbot.domain.eval;

import java.util.Map;

/** One real or replay provider/model cell with explicit token pricing. */
public record EvalModelTarget(
        String id,
        String provider,
        String model,
        double inputUsdPerMillionTokens,
        double outputUsdPerMillionTokens,
        Map<String, Object> metadata
) {
    public EvalModelTarget {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        provider = provider != null ? provider.trim() : "";
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        inputUsdPerMillionTokens = Math.max(0d, inputUsdPerMillionTokens);
        outputUsdPerMillionTokens = Math.max(0d, outputUsdPerMillionTokens);
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
