package ricbot.domain.retrieval;

public final class HybridScoring {
    private HybridScoring() { }
    public static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) return 0d;
        double dot = 0d, a = 0d, b = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            a += left[i] * left[i];
            b += right[i] * right[i];
        }
        return a == 0d || b == 0d ? 0d : dot / Math.sqrt(a * b);
    }
}
