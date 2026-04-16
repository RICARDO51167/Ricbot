package ricbot.integration.llm.api;

/**
 * 对应 Python: GenerationSettings
 */
public class GenerationSettings {

    private double temperature = 0.7;

    private int maxTokens = 4096;

    private String reasoningEffort;

    public GenerationSettings() {
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    @Override
    public String toString() {
        return "GenerationSettings{" +
                "temperature=" + temperature +
                ", maxTokens=" + maxTokens +
                ", reasoningEffort='" + reasoningEffort + '\'' +
                '}';
    }
}