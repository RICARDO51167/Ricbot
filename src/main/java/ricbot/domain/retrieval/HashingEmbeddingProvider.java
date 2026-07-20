package ricbot.domain.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Deterministic offline fallback; production can inject a model embedding provider. */
public final class HashingEmbeddingProvider implements EmbeddingProvider {
    private final int dimensions;

    public HashingEmbeddingProvider() { this(384); }
    public HashingEmbeddingProvider(int dimensions) {
        if (dimensions < 32) throw new IllegalArgumentException("dimensions must be at least 32");
        this.dimensions = dimensions;
    }
    public String modelId() { return "feature-hash-char-token-v1:" + dimensions; }

    public double[] embed(String text) {
        double[] vector = new double[dimensions];
        String normalized = text != null ? text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim() : "";
        for (String token : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+")) {
            if (!token.isBlank()) add(vector, "t:" + token, 1.5d);
        }
        String compact = normalized.replace(" ", "");
        for (int i = 0; i + 3 <= compact.length(); i++) add(vector, "c:" + compact.substring(i, i + 3), 0.5d);
        double norm = 0d;
        for (double value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm > 0d) for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    private void add(double[] vector, String feature, double weight) {
        byte[] bytes = feature.getBytes(StandardCharsets.UTF_8);
        int hash = java.util.Arrays.hashCode(bytes);
        int index = (hash & Integer.MAX_VALUE) % vector.length;
        vector[index] += (hash & 1) == 0 ? weight : -weight;
    }
}
