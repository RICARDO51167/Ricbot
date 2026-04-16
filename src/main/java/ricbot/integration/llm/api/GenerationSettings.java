package ricbot.integration.llm.api;

/**
 * 对应 Python: GenerationSettings
 *
 * 主要目标：
 * 1. 保存默认生成参数
 */
public class GenerationSettings {

    // 温度参数，控制生成文本的随机性，默认值为 0.7
    private double temperature = 0.7;

    // 最大生成令牌数，限制输出长度，默认值为 4096
    private int maxTokens = 4096;

    // 推理努力程度，用于控制模型推理的深度或复杂度
    private String reasoningEffort;

    // 无参构造函数
    public GenerationSettings() {
    }

    // 获取温度参数
    public double getTemperature() {
        return temperature;
    }

    // 设置温度参数
    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    // 获取最大生成令牌数
    public int getMaxTokens() {
        return maxTokens;
    }

    // 设置最大生成令牌数
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    // 获取推理努力程度
    public String getReasoningEffort() {
        return reasoningEffort;
    }

    // 设置推理努力程度
    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    // 重写 toString 方法，返回对象的字符串表示
    @Override
    public String toString() {
        return "GenerationSettings{" +
                "temperature=" + temperature +
                ", maxTokens=" + maxTokens +
                ", reasoningEffort='" + reasoningEffort + '\'' +
                '}';
    }
}