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
        // 声明配置对象变量
        Config config;
        // 检查配置文件路径是否非空且非空白
        if (configPath != null && !configPath.isBlank()) {
            // 解析配置文件路径为绝对路径并规范化
            Path resolved = Path.of(configPath).toAbsolutePath().normalize();
            // 检查文件是否存在，不存在则抛出异常
            if (!Files.exists(resolved)) {
                throw new IllegalArgumentException("Config file not found: " + resolved);
            }
            // 设置配置加载器的配置路径
            ConfigLoader.setConfigPath(resolved);
            // 从指定路径加载配置
            config = ConfigLoader.loadConfig(resolved);
        } else {
            // 使用默认配置加载
            config = ConfigLoader.loadConfig();
        }

        // 检查工作区覆盖路径是否非空且非空白
        if (workspaceOverride != null && !workspaceOverride.isBlank()) {
            // 设置代理默认配置中的工作区路径
            config.getAgents().getDefaults().setWorkspace(workspaceOverride);
        }

        // 返回加载后的配置对象
        return config;
    }

    /**
     * 创建消息总线实例。
     *
     * @return 新的 MessageBus 实例
     */
    public MessageBus createBus() {
        // 创建并返回新的消息总线实例
        return new MessageBus();
    }

    /**
     * 根据配置创建 LLM 提供者实例。
     *
     * @param config 配置对象
     * @return LLMProvider 实例
     */
    public LLMProvider createProvider(Config config) {
        // 通过工厂方法根据配置创建并返回 LLM 提供者实例
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
        // 获取代理的默认配置
        Config.AgentDefaults defaults = config.getAgents().getDefaults();

        // 创建并返回 AgentLoop 实例，传入各种配置参数
        return new AgentLoop(
                bus, // 消息总线
                provider, // LLM 提供者
                config.getWorkspacePath(), // 工作区路径
                defaults.getModel(), // 模型名称
                defaults.getMaxToolIterations(), // 最大工具迭代次数
                defaults.getContextWindowTokens(), // 上下文窗口令牌数
                defaults.getContextBlockLimit(), // 上下文块限制
                defaults.getMaxToolResultChars(), // 最大工具结果字符数
                defaults.getProviderRetryMode(), // 提供者重试模式
                config.getTools().getWeb(), // Web 工具配置
                config.getTools().getExec(), // 执行工具配置
                config.getTools().getMcpServers(), // MCP 服务器配置
                config.getTools().isRestrictToWorkspace(), // 是否限制在工作区内
                null, // 保留参数，当前为 null
                defaults.getTimezone(), // 时区
                defaults.isUnifiedSession(), // 是否统一会话
                defaults.getDisabledSkills(), // 禁用的技能列表
                defaults.getSessionTtlMinutes(), // 会话 TTL（分钟）
                defaults.getDream() // Dream 配置
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
        // 获取网关配置
        Config.GatewayConfig gateway = config.getGateway();
        // 获取心跳配置
        Config.HeartbeatConfig hb = gateway.getHeartbeat();
        // 获取代理默认配置
        Config.AgentDefaults defaults = config.getAgents().getDefaults();

        // 创建并返回心跳服务实例
        return new HeartbeatService(
                config.getWorkspacePath(), // 工作区路径
                provider, // LLM 提供者
                defaults.getModel(), // 模型名称
                onExecute, // 执行处理器
                onNotify, // 通知处理器
                hb.getIntervalS(), // 心跳间隔（秒）
                hb.isEnabled(), // 是否启用心跳
                defaults.getTimezone() // 时区
        );
    }
}
