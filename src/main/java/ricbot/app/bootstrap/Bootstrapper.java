package ricbot.app.bootstrap;

import ricbot.domain.agent.AgentLoop;
import ricbot.domain.agent.AgentRuntimeCore;
import ricbot.domain.agent.AgentRuntimeCoreFactory;
import ricbot.domain.config.ProviderCapabilityResolver;
import ricbot.domain.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.provider.ProviderFactory;
import ricbot.tool.pack.RuntimeToolPacks;
import ricbot.integration.channel.ChannelManager;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动引导类，负责初始化核心组件。
 */
public class Bootstrapper {

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

    public MessageBus createBus() {
        return new MessageBus();
    }

    public LLMProvider createProvider(Config config) {
        return ProviderFactory.makeProvider(config);
    }

    public AgentLoop createAgentLoop(Config config, MessageBus bus, LLMProvider provider) {
        Config.AgentDefaults defaults = config.getAgents().getDefaults();
        String model = defaults.getModel() != null ? defaults.getModel() : provider.getDefaultModel();
        AgentRuntimeCore core = AgentRuntimeCoreFactory.create(
                provider,
                config.getWorkspacePath(),
                model,
                defaults.getContextWindowTokens(),
                defaults.getMaxToolResultChars(),
                config.getTools().getWeb(),
                config.getTools().getExec(),
                config.getTools().isRestrictToWorkspace(),
                null,
                defaults.getTimezone(),
                defaults.getDisabledSkills(),
                defaults.getSessionTtlMinutes()
        );
        RuntimeToolPacks.registerAll(core.tools(), config.getWorkspacePath(),
                config.getTools().isRestrictToWorkspace(), config.getTools().getExec(),
                config.getTools().getWeb(), core.approvalService(), core.skillsLoader(), core.spawnWorkers());

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
                config.getTools().getWeb(),
                config.getTools().getExec(),
                config.getTools().getMcpServers(),
                config.getTools().isRestrictToWorkspace(),
                null,
                defaults.getTimezone(),
                defaults.isUnifiedSession(),
                defaults.getDisabledSkills(),
                defaults.getSessionTtlMinutes(),
                core
        );
        loop.setProviderCapability(new ProviderCapabilityResolver().resolve(
                config,
                config.getProviderName(defaults.getModel()),
                defaults.getModel()
        ));
        return loop;
    }

    public ChannelManager createChannelManager(Config config, MessageBus bus) {
        return new ChannelManager(config, bus);
    }
}
