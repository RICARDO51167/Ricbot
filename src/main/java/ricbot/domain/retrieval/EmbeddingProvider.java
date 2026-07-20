package ricbot.domain.retrieval;

public interface EmbeddingProvider {
    String modelId();
    double[] embed(String text);
}
