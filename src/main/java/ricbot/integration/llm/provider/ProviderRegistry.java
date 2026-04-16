package ricbot.integration.llm.provider;

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

    // 私有构造函数，防止实例化
    private ProviderRegistry() {
    }

    /**
     * 对应 Python: PROVIDERS
     *
     * 这里只把核心 provider 全补齐到你当前转写链路会用到的程度。
     * 后面你要继续扩，我也能继续补。
     */
    public static final List<ProviderSpec> PROVIDERS = List.of(
            // 自定义提供商配置
            new ProviderSpec("custom", List.of(), "", "自定义", "openai_compat")
                    .setDirect(true), // 设置为直连模式

            // Azure OpenAI 提供商配置
            new ProviderSpec("azure_openai", List.of("azure", "azure-openai"), "", "Azure OpenAI", "azure_openai")
                    .setDirect(true), // 设置为直连模式

            // 网关类提供商配置开始

            // OpenRouter 网关配置
            new ProviderSpec("openrouter", List.of("openrouter"), "OPENROUTER_API_KEY", "OpenRouter", "openai_compat")
                    .setGateway(true) // 标记为网关模式
                    .setDetectByKeyPrefix("sk-or-") // 通过 API Key 前缀检测
                    .setDetectByBaseKeyword("openrouter") // 通过 Base URL 关键字检测
                    .setDefaultApiBase("https://openrouter.ai/api/v1") // 默认 API 基础地址
                    .setSupportsPromptCaching(true), // 支持提示词缓存

            // AiHubMix 网关配置
            new ProviderSpec("aihubmix", List.of("aihubmix"), "OPENAI_API_KEY", "AiHubMix", "openai_compat")
                    .setGateway(true) // 标记为网关模式
                    .setDetectByBaseKeyword("aihubmix") // 通过 Base URL 关键字检测
                    .setDefaultApiBase("https://aihubmix.com/v1") // 默认 API 基础地址
                    .setStripModelPrefix(true), // 去除模型名称前缀

            // SiliconFlow (硅基流动) 网关配置
            new ProviderSpec("siliconflow", List.of("siliconflow"), "OPENAI_API_KEY", "SiliconFlow", "openai_compat")
                    .setGateway(true) // 标记为网关模式
                    .setDetectByBaseKeyword("siliconflow") // 通过 Base URL 关键字检测
                    .setDefaultApiBase("https://api.siliconflow.cn/v1"), // 默认 API 基础地址

            // VolcEngine (火山引擎) 网关配置
            new ProviderSpec("volcengine", List.of("volcengine", "volces", "ark"), "OPENAI_API_KEY", "VolcEngine", "openai_compat")
                    .setGateway(true) // 标记为网关模式
                    .setDetectByBaseKeyword("volces") // 通过 Base URL 关键字检测
                    .setDefaultApiBase("https://ark.cn-beijing.volces.com/api/v3"), // 默认 API 基础地址

            // VolcEngine Coding Plan (火山引擎代码计划) 网关配置
            new ProviderSpec("volcengine_coding_plan", List.of("volcengine-plan"), "OPENAI_API_KEY", "VolcEngine Coding Plan", "openai_compat")
                    .setGateway(true) // 标记为网关模式
                    .setDefaultApiBase("https://ark.cn-beijing.volces.com/api/coding/v3") // 默认 API 基础地址
                    .setStripModelPrefix(true), // 去除模型名称前缀

            // BytePlus (字节海外) 网关配置
            new ProviderSpec("byteplus", List.of("byteplus"), "OPENAI_API_KEY", "BytePlus", "openai_compat")
                    .setGateway(true) // 标记为网关模式
                    .setDetectByBaseKeyword("bytepluses") // 通过 Base URL 关键字检测
                    .setDefaultApiBase("https://ark.ap-southeast.bytepluses.com/api/v3") // 默认 API 基础地址
                    .setStripModelPrefix(true), // 去除模型名称前缀

            // BytePlus Coding Plan (字节海外代码计划) 网关配置
            new ProviderSpec("byteplus_coding_plan", List.of("byteplus-plan"), "OPENAI_API_KEY", "BytePlus Coding Plan", "openai_compat")
                    .setGateway(true) // 标记为网关模式
                    .setDefaultApiBase("https://ark.ap-southeast.bytepluses.com/api/coding/v3") // 默认 API 基础地址
                    .setStripModelPrefix(true), // 去除模型名称前缀

            // 标准提供商配置开始

            // Anthropic (Claude) 提供商配置
            new ProviderSpec("anthropic", List.of("anthropic", "claude"), "ANTHROPIC_API_KEY", "Anthropic", "anthropic")
                    .setSupportsPromptCaching(true), // 支持提示词缓存

            // OpenAI 提供商配置
            new ProviderSpec("openai", List.of("openai", "gpt"), "OPENAI_API_KEY", "OpenAI", "openai_compat")
                    .setDefaultApiBase("https://api.openai.com/v1") // 默认 API 基础地址
                    .setSupportsMaxCompletionTokens(true), // 支持最大完成令牌数设置

            // OpenAI Codex 提供商配置
            new ProviderSpec("openai_codex", List.of("openai-codex"), "", "OpenAI Codex", "openai_codex")
                    .setDetectByBaseKeyword("codex") // 通过 Base URL 关键字检测
                    .setDefaultApiBase("https://chatgpt.com/backend-api") // 默认 API 基础地址
                    .setOauth(true), // 使用 OAuth 认证

            // GitHub Copilot 提供商配置
            new ProviderSpec("github_copilot", List.of("github_copilot", "copilot"), "", "Github Copilot", "github_copilot")
                    .setDefaultApiBase("https://api.githubcopilot.com") // 默认 API 基础地址
                    .setStripModelPrefix(true) // 去除模型名称前缀
                    .setOauth(true), // 使用 OAuth 认证

            // DeepSeek (深度求索) 提供商配置
            new ProviderSpec("deepseek", List.of("deepseek"), "DEEPSEEK_API_KEY", "DeepSeek", "openai_compat")
                    .setDefaultApiBase("https://api.deepseek.com"), // 默认 API 基础地址

            // Gemini (Google) 提供商配置
            new ProviderSpec("gemini", List.of("gemini"), "GEMINI_API_KEY", "Gemini", "openai_compat")
                    .setDefaultApiBase("https://generativelanguage.googleapis.com/v1beta/openai/"), // 默认 API 基础地址

            // Zhipu AI (智谱) 提供商配置
            new ProviderSpec("zhipu", List.of("zhipu", "glm", "zai"), "ZAI_API_KEY", "Zhipu AI", "openai_compat")
                    .setEnvExtras(List.of(
                            new ProviderSpec.EnvExtra("ZHIPUAI_API_KEY", "{api_key}") // 额外环境变量映射
                    ))
                    .setDefaultApiBase("https://open.bigmodel.cn/api/paas/v4"), // 默认 API 基础地址

            // DashScope (阿里通义千问) 提供商配置
            new ProviderSpec("dashscope", List.of("qwen", "dashscope"), "DASHSCOPE_API_KEY", "DashScope", "openai_compat")
                    .setDefaultApiBase("https://dashscope.aliyuncs.com/compatible-mode/v1"), // 默认 API 基础地址

            // Moonshot (月之暗面/Kimi) 提供商配置
            new ProviderSpec("moonshot", List.of("moonshot", "kimi"), "MOONSHOT_API_KEY", "Moonshot", "openai_compat")
                    .setDefaultApiBase("https://api.moonshot.ai/v1") // 默认 API 基础地址
                    .setModelOverrides(List.of(
                            new ProviderSpec.ModelOverride("kimi-k2.5", java.util.Map.of("temperature", 1.0)) // 模型参数覆盖
                    )),

            // MiniMax 提供商配置
            new ProviderSpec("minimax", List.of("minimax"), "MINIMAX_API_KEY", "MiniMax", "openai_compat")
                    .setDefaultApiBase("https://api.minimax.io/v1"), // 默认 API 基础地址

            // Mistral 提供商配置
            new ProviderSpec("mistral", List.of("mistral"), "MISTRAL_API_KEY", "Mistral", "openai_compat")
                    .setDefaultApiBase("https://api.mistral.ai/v1"), // 默认 API 基础地址

            // StepFun (阶跃星辰) 提供商配置
            new ProviderSpec("stepfun", List.of("stepfun", "step"), "STEPFUN_API_KEY", "Step Fun", "openai_compat")
                    .setDefaultApiBase("https://api.stepfun.com/v1"), // 默认 API 基础地址

            // Xiaomi MIMO (小米) 提供商配置
            new ProviderSpec("xiaomi_mimo", List.of("xiaomi_mimo", "mimo"), "XIAOMIMIMO_API_KEY", "Xiaomi MIMO", "openai_compat")
                    .setDefaultApiBase("https://api.xiaomimimo.com/v1"), // 默认 API 基础地址

            // 本地部署提供商配置开始

            // vLLM 本地部署配置
            new ProviderSpec("vllm", List.of("vllm"), "HOSTED_VLLM_API_KEY", "vLLM/本地", "openai_compat")
                    .setLocal(true), // 标记为本地部署

            // Ollama 本地部署配置
            new ProviderSpec("ollama", List.of("ollama", "nemotron"), "OLLAMA_API_KEY", "Ollama", "openai_compat")
                    .setLocal(true) // 标记为本地部署
                    .setDetectByBaseKeyword("11434") // 通过 Base URL 关键字检测 (默认端口)
                    .setDefaultApiBase("http://localhost:11434/v1"), // 默认 API 基础地址

            // OpenVINO Model Server (OVMS) 本地部署配置
            new ProviderSpec("ovms", List.of("openvino", "ovms"), "", "OpenVINO Model Server", "openai_compat")
                    .setDirect(true) // 设置为直连模式
                    .setLocal(true) // 标记为本地部署
                    .setDefaultApiBase("http://localhost:8000/v3") // 默认 API 基础地址
    );

    /**
     * 对应 Python: find_by_name(name)
     * 根据名称查找 ProviderSpec
     *
     * @param name 提供商名称
     * @return 匹配的 ProviderSpec，未找到返回 null
     */
    public static ProviderSpec findByName(String name) {
        // 检查名称是否为空或空白
        if (name == null || name.isBlank()) {
            return null;
        }
        // 遍历所有提供商规格
        for (ProviderSpec spec : PROVIDERS) {
            // 如果名称匹配，则返回该规格
            if (spec.getName().equals(name)) {
                return spec;
            }
        }
        // 未找到匹配项
        return null;
    }

    /**
     * 按模型关键字查找
     * 根据模型名称中包含的关键字查找对应的 ProviderSpec
     *
     * @param modelName 模型名称
     * @return 匹配的 ProviderSpec，未找到返回 null
     */
    public static ProviderSpec findByModelKeyword(String modelName) {
        // 检查模型名称是否为空或空白
        if (modelName == null || modelName.isBlank()) {
            return null;
        }

        // 将模型名称转换为小写以便进行不区分大小写的比较
        String lower = modelName.toLowerCase(Locale.ROOT);
        // 遍历所有提供商规格
        for (ProviderSpec spec : PROVIDERS) {
            // 遍历当前提供商的所有关键字
            for (String kw : spec.getKeywords()) {
                // 如果模型名称包含关键字，则返回该规格
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    return spec;
                }
            }
        }
        // 未找到匹配项
        return null;
    }

    /**
     * 按 api key 前缀查找网关
     * 根据 API Key 的前缀识别对应的网关提供商
     *
     * @param apiKey API Key
     * @return 匹配的 ProviderSpec，未找到返回 null
     */
    public static ProviderSpec findByKeyPrefix(String apiKey) {
        // 检查 API Key 是否为空或空白
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        // 遍历所有提供商规格
        for (ProviderSpec spec : PROVIDERS) {
            // 获取用于检测的 Key 前缀
            String prefix = spec.getDetectByKeyPrefix();
            // 如果前缀存在且 API Key 以该前缀开头，则返回该规格
            if (prefix != null && !prefix.isBlank() && apiKey.startsWith(prefix)) {
                return spec;
            }
        }
        // 未找到匹配项
        return null;
    }

    /**
     * 按 api_base 关键字查找
     * 根据 API Base URL 中包含的关键字查找对应的提供商
     *
     * @param apiBase API 基础地址
     * @return 匹配的 ProviderSpec，未找到返回 null
     */
    public static ProviderSpec findByBaseKeyword(String apiBase) {
        // 检查 API Base 是否为空或空白
        if (apiBase == null || apiBase.isBlank()) {
            return null;
        }
        // 将 API Base 转换为小写以便进行不区分大小写的比较
        String lower = apiBase.toLowerCase(Locale.ROOT);

        // 遍历所有提供商规格
        for (ProviderSpec spec : PROVIDERS) {
            // 获取用于检测的 Base 关键字
            String keyword = spec.getDetectByBaseKeyword();
            // 如果关键字存在且 API Base 包含该关键字，则返回该规格
            if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return spec;
            }
        }
        // 未找到匹配项
        return null;
    }

}
