package ricbot.llm.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 对应 Python: registry.py
 *
 * 主要目标：
 * 1. 保存全部 ProviderSpec
 * 2. 提供 findByName / findByKeyword 等辅助方法
 */
public final class ProviderRegistry {

    private ProviderRegistry() {
    }

    /**
     * 对应 Python: PROVIDERS
     *
     * 这里只把核心 provider 全补齐到你当前转写链路会用到的程度。
     * 后面你要继续扩，我也能继续补。
     */
    public static final List<ProviderSpec> PROVIDERS = List.of(
            // custom
            new ProviderSpec("custom", List.of(), "", "Custom", "openai_compat")
                    .setDirect(true),

            // azure_openai
            new ProviderSpec("azure_openai", List.of("azure", "azure-openai"), "", "Azure OpenAI", "azure_openai")
                    .setDirect(true),

            // gateways
            new ProviderSpec("openrouter", List.of("openrouter"), "OPENROUTER_API_KEY", "OpenRouter", "openai_compat")
                    .setGateway(true)
                    .setDetectByKeyPrefix("sk-or-")
                    .setDetectByBaseKeyword("openrouter")
                    .setDefaultApiBase("https://openrouter.ai/api/v1")
                    .setSupportsPromptCaching(true),

            new ProviderSpec("aihubmix", List.of("aihubmix"), "OPENAI_API_KEY", "AiHubMix", "openai_compat")
                    .setGateway(true)
                    .setDetectByBaseKeyword("aihubmix")
                    .setDefaultApiBase("https://aihubmix.com/v1")
                    .setStripModelPrefix(true),

            new ProviderSpec("siliconflow", List.of("siliconflow"), "OPENAI_API_KEY", "SiliconFlow", "openai_compat")
                    .setGateway(true)
                    .setDetectByBaseKeyword("siliconflow")
                    .setDefaultApiBase("https://api.siliconflow.cn/v1"),

            new ProviderSpec("volcengine", List.of("volcengine", "volces", "ark"), "OPENAI_API_KEY", "VolcEngine", "openai_compat")
                    .setGateway(true)
                    .setDetectByBaseKeyword("volces")
                    .setDefaultApiBase("https://ark.cn-beijing.volces.com/api/v3"),

            new ProviderSpec("volcengine_coding_plan", List.of("volcengine-plan"), "OPENAI_API_KEY", "VolcEngine Coding Plan", "openai_compat")
                    .setGateway(true)
                    .setDefaultApiBase("https://ark.cn-beijing.volces.com/api/coding/v3")
                    .setStripModelPrefix(true),

            new ProviderSpec("byteplus", List.of("byteplus"), "OPENAI_API_KEY", "BytePlus", "openai_compat")
                    .setGateway(true)
                    .setDetectByBaseKeyword("bytepluses")
                    .setDefaultApiBase("https://ark.ap-southeast.bytepluses.com/api/v3")
                    .setStripModelPrefix(true),

            new ProviderSpec("byteplus_coding_plan", List.of("byteplus-plan"), "OPENAI_API_KEY", "BytePlus Coding Plan", "openai_compat")
                    .setGateway(true)
                    .setDefaultApiBase("https://ark.ap-southeast.bytepluses.com/api/coding/v3")
                    .setStripModelPrefix(true),

            // standard
            new ProviderSpec("anthropic", List.of("anthropic", "claude"), "ANTHROPIC_API_KEY", "Anthropic", "anthropic")
                    .setSupportsPromptCaching(true),

            new ProviderSpec("openai", List.of("openai", "gpt"), "OPENAI_API_KEY", "OpenAI", "openai_compat")
                    .setDefaultApiBase("https://api.openai.com/v1")
                    .setSupportsMaxCompletionTokens(true),

            new ProviderSpec("openai_codex", List.of("openai-codex"), "", "OpenAI Codex", "openai_codex")
                    .setDetectByBaseKeyword("codex")
                    .setDefaultApiBase("https://chatgpt.com/backend-api")
                    .setOauth(true),

            new ProviderSpec("github_copilot", List.of("github_copilot", "copilot"), "", "Github Copilot", "github_copilot")
                    .setDefaultApiBase("https://api.githubcopilot.com")
                    .setStripModelPrefix(true)
                    .setOauth(true),

            new ProviderSpec("deepseek", List.of("deepseek"), "DEEPSEEK_API_KEY", "DeepSeek", "openai_compat")
                    .setDefaultApiBase("https://api.deepseek.com"),

            new ProviderSpec("gemini", List.of("gemini"), "GEMINI_API_KEY", "Gemini", "openai_compat")
                    .setDefaultApiBase("https://generativelanguage.googleapis.com/v1beta/openai/"),

            new ProviderSpec("zhipu", List.of("zhipu", "glm", "zai"), "ZAI_API_KEY", "Zhipu AI", "openai_compat")
                    .setEnvExtras(List.of(
                            new ProviderSpec.EnvExtra("ZHIPUAI_API_KEY", "{api_key}")
                    ))
                    .setDefaultApiBase("https://open.bigmodel.cn/api/paas/v4"),

            new ProviderSpec("dashscope", List.of("qwen", "dashscope"), "DASHSCOPE_API_KEY", "DashScope", "openai_compat")
                    .setDefaultApiBase("https://dashscope.aliyuncs.com/compatible-mode/v1"),

            new ProviderSpec("moonshot", List.of("moonshot", "kimi"), "MOONSHOT_API_KEY", "Moonshot", "openai_compat")
                    .setDefaultApiBase("https://api.moonshot.ai/v1")
                    .setModelOverrides(List.of(
                            new ProviderSpec.ModelOverride("kimi-k2.5", java.util.Map.of("temperature", 1.0))
                    )),

            new ProviderSpec("minimax", List.of("minimax"), "MINIMAX_API_KEY", "MiniMax", "openai_compat")
                    .setDefaultApiBase("https://api.minimax.io/v1"),

            new ProviderSpec("mistral", List.of("mistral"), "MISTRAL_API_KEY", "Mistral", "openai_compat")
                    .setDefaultApiBase("https://api.mistral.ai/v1"),

            new ProviderSpec("stepfun", List.of("stepfun", "step"), "STEPFUN_API_KEY", "Step Fun", "openai_compat")
                    .setDefaultApiBase("https://api.stepfun.com/v1"),

            new ProviderSpec("xiaomi_mimo", List.of("xiaomi_mimo", "mimo"), "XIAOMIMIMO_API_KEY", "Xiaomi MIMO", "openai_compat")
                    .setDefaultApiBase("https://api.xiaomimimo.com/v1"),

            // local
            new ProviderSpec("vllm", List.of("vllm"), "HOSTED_VLLM_API_KEY", "vLLM/Local", "openai_compat")
                    .setLocal(true),

            new ProviderSpec("ollama", List.of("ollama", "nemotron"), "OLLAMA_API_KEY", "Ollama", "openai_compat")
                    .setLocal(true)
                    .setDetectByBaseKeyword("11434")
                    .setDefaultApiBase("http://localhost:11434/v1"),

            new ProviderSpec("ovms", List.of("openvino", "ovms"), "", "OpenVINO Model Server", "openai_compat")
                    .setDirect(true)
                    .setLocal(true)
                    .setDefaultApiBase("http://localhost:8000/v3")
    );

    /**
     * 对应 Python: find_by_name(name)
     */
    public static ProviderSpec findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ProviderSpec spec : PROVIDERS) {
            if (spec.getName().equals(name)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 按模型关键字查找
     */
    public static ProviderSpec findByModelKeyword(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }

        String lower = modelName.toLowerCase(Locale.ROOT);
        for (ProviderSpec spec : PROVIDERS) {
            for (String kw : spec.getKeywords()) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    return spec;
                }
            }
        }
        return null;
    }

    /**
     * 按 api key 前缀查找 gateway
     */
    public static ProviderSpec findByKeyPrefix(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        for (ProviderSpec spec : PROVIDERS) {
            String prefix = spec.getDetectByKeyPrefix();
            if (prefix != null && !prefix.isBlank() && apiKey.startsWith(prefix)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 按 api_base 关键字查找
     */
    public static ProviderSpec findByBaseKeyword(String apiBase) {
        if (apiBase == null || apiBase.isBlank()) {
            return null;
        }
        String lower = apiBase.toLowerCase(Locale.ROOT);

        for (ProviderSpec spec : PROVIDERS) {
            String keyword = spec.getDetectByBaseKeyword();
            if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 对应 Python 里 PROVIDERS 的完整枚举访问
     */
    public static List<String> providerNames() {
        List<String> names = new ArrayList<>();
        for (ProviderSpec spec : PROVIDERS) {
            names.add(spec.getName());
        }
        return names;
    }
}
