package ricbot.app.bootstrap;

import ricbot.domain.agent.AgentLoop;
import ricbot.domain.agent.dto.AgentRuntimeCore;
import ricbot.domain.agent.AgentRuntimeCoreFactory;
import ricbot.domain.config.ProviderCapabilityResolver;
import ricbot.domain.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.provider.ProviderFactory;
import ricbot.tool.pack.RuntimeToolPacks;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动引导类，负责初始化核心组件。
 */
public class Bootstrapper {

    /**
     * 加载并解析配置文件。
     * 支持从指定路径加载或加载默认配置，并允许覆盖工作空间路径。
     *
     * @param configPath 配置文件路径，如果为null则使用默认路径
     * @param workspaceOverride 工作空间覆盖路径，如果为null则不覆盖
     * @return 解析后的配置对象
     */
    public Config loadConfig(String configPath, String workspaceOverride) {
        Config config;
        if (configPath != null && !configPath.isBlank()) {
            Path resolved = Path.of(configPath).toAbsolutePath().normalize();
            if (!Files.exists(resolved)) {
                throw new IllegalArgumentException("Config file not found: " + resolved);
            }
            ConfigLoader.setConfigPath(resolved);
            config = ConfigLoader.loadConfig(resolved);
        } else {
            config = ConfigLoader.loadConfig();
        }

        if (workspaceOverride != null && !workspaceOverride.isBlank()) {
            config.getAgents().getDefaults().setWorkspace(workspaceOverride);
        }

        return config;
    }

    /**
     * 根据配置创建LLM提供者实例。
     *
     * @param config 包含LLM provider信息的配置对象
     * @return 初始化好的LLM提供者
     */
    public LLMProvider createProvider(Config config) {
        return ProviderFactory.makeProvider(config);
    }

    /**
     * 创建并配置Agent循环逻辑。
     * 此方法负责初始化Agent运行时核心、注册工具包以及配置Agent循环所需的参数。
     *
     * @param config 系统配置对象
     * @param bus 消息总线实例
     * @param provider LLM提供者实例
     * @return 初始化完成的Agent循环实例
     */
    public AgentLoop createAgentLoop(Config config, MessageBus bus, LLMProvider provider) {
        // 获取代理默认配置，若未指定模型则使用provider的默认模型
        Config.AgentDefaults defaults = config.getAgents().getDefaults();
        String model = defaults.getModel() != null ? defaults.getModel() : provider.getDefaultModel();
        String providerName = config.getProviderName(model);
        ricbot.domain.config.ModelCard modelCard = new ricbot.domain.config.ModelCardResolver()
                .resolve(config, providerName, model).orElse(null);
        if (defaults.getBudget().getMaxCostMicrousd() != null
                && (modelCard == null || modelCard.pricing() == null || !modelCard.pricing().known())) {
            throw new IllegalArgumentException("cost budget requires a priced model card: provider="
                    + providerName + ", model=" + model);
        }
        
        // 1. 创建Agent运行时核心
        AgentRuntimeCore core = AgentRuntimeCoreFactory.create(
                provider,
                config.getWorkspacePath(),
                model,
                defaults.getContextWindowTokens(),
                defaults.getMaxToolResultChars(),
                config.getTools().getExec(),
                config.getTools().isRestrictToWorkspace(),
                null,
                defaults.getTimezone(),
                defaults.getSessionTtlMinutes(),
                new ricbot.domain.agent.budget.BudgetPolicy(defaults.getBudget().getMaxTotalTokens(),
                        defaults.getBudget().getMaxCostMicrousd(), defaults.getBudget().getMaxActiveSeconds(),
                        defaults.getBudget().getMaxToolCalls(), defaults.getBudget().getFinalizationTokens(), ""),
                defaults.getContextOffload(), modelCard != null ? modelCard.pricing() : null
        );
        
        // 2. 注册所有运行时工具包
        RuntimeToolPacks.registerAll(core.tools(), config.getWorkspacePath(),
                config.getTools().isRestrictToWorkspace(), config.getTools().getExec(),
                core.approvalService());

        // 3. 构建Agent循环实例
        AgentLoop loop = new AgentLoop(
                bus,
                provider,
                config.getWorkspacePath(),
                model,
                defaults.getMaxToolIterations(),
                defaults.getContextWindowTokens(),
                defaults.getContextBlockLimit(),
                defaults.getMaxToolResultChars(),
                defaults.getProviderRetryMode(),
                config.getTools().getExec(),
                config.getTools().isRestrictToWorkspace(),
                null,
                defaults.getTimezone(),
                defaults.isUnifiedSession(),
                defaults.getSessionTtlMinutes(),
                core,
                defaults.getBudget(),
                defaults.getContextOffload()
        );
        
        // 4. 解析并设置提供商能力
        loop.setProviderCapability(new ProviderCapabilityResolver().resolve(
                config,
                providerName,
                model
        ));
        loop.setModelPricing(modelCard != null ? modelCard.pricing() : null);
        
        return loop;
    }

}
