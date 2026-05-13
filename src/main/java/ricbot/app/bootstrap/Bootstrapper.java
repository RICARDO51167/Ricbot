package ricbot.app.bootstrap;

import ricbot.domain.agent.AgentLoop;
import ricbot.domain.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;
import ricbot.infra.heartbeat.HeartbeatService;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.provider.ProviderFactory;
import ricbot.integration.channel.ChannelManager;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动引导类，负责初始化核心组件。
 */
public class Bootstrapper {

    /**
     * 加载配置信息。
     *
     * @param configPath       配置文件路径，如果为空则使用默认配置
     * @param workspaceOverride 工作区覆盖路径，如果不为空则覆盖配置中的工作区设置
     * @return 加载后的 Config 对象
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
     * 创建消息总线实例。
     *
     * @return 新的 MessageBus 实例
     */
    public MessageBus createBus() {
        return new MessageBus();
    }

    /**
     * 根据配置创建 LLM 提供者实例。
     *
     * @param config 配置对象
     * @return LLMProvider 实例
     */
    public LLMProvider createProvider(Config config) {
        return ProviderFactory.makeProvider(config);
    }

    /**
     * 创建 Agent 循环实例。
     *
     * @param config   配置对象
     * @param bus      消息总线
     * @param provider LLM 提供者
     * @return AgentLoop 实例
     */
    public AgentLoop createAgentLoop(Config config, MessageBus bus, LLMProvider provider) {
        Config.AgentDefaults defaults = config.getAgents().getDefaults();

        return new AgentLoop(
                bus,
                provider,
                config.getWorkspacePath(),
                defaults.getModel(),
                defaults.getMaxToolIterations(),
                defaults.getContextWindowTokens(),
                defaults.getContextBlockLimit(),
                defaults.getMaxToolResultChars(),
                defaults.getProviderRetryMode(),
                config.getTools().getWeb(),
                config.getTools().getExec(),
                config.getTools().getMcpServers(),
                config.getTools().isRestrictToWorkspace(),
                null,
                defaults.getTimezone(),
                defaults.isUnifiedSession(),
                defaults.getDisabledSkills(),
                defaults.getSessionTtlMinutes(),
                defaults.getDream()
        );
    }

    /**
     * 创建渠道管理器实例。
     *
     * @param config 配置对象
     * @param bus    消息总线
     * @return ChannelManager 实例
     */
    public ChannelManager createChannelManager(Config config, MessageBus bus) {
        return new ChannelManager(config, bus);
    }

    /**
     * 创建心跳服务实例。
     *
     * @param config    配置对象
     * @param provider  LLM 提供者
     * @param onExecute 执行处理器
     * @param onNotify  通知处理器
     * @return HeartbeatService 实例
     */
    public HeartbeatService createHeartbeatService(
            Config config,
            LLMProvider provider,
            HeartbeatService.ExecuteHandler onExecute,
            HeartbeatService.NotifyHandler onNotify
    ) {
        Config.GatewayConfig gateway = config.getGateway();
        Config.HeartbeatConfig hb = gateway.getHeartbeat();
        Config.AgentDefaults defaults = config.getAgents().getDefaults();

        return new HeartbeatService(
                config.getWorkspacePath(),
                provider,
                defaults.getModel(),
                onExecute,
                onNotify,
                hb.getIntervalS(),
                hb.isEnabled(),
                defaults.getTimezone()
        );
    }
}
