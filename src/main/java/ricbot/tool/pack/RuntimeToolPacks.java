package ricbot.tool.pack;

import ricbot.domain.agent.SpawnWorkerService;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.skill.SkillsLoader;
import ricbot.infra.config.Config;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;

/** Single bootstrap entry that composes the core and optional tool packs into one registry. */
public final class RuntimeToolPacks {
    private RuntimeToolPacks() {
    }

    public static void registerAll(
            ToolRegistry tools,
            Path workspace,
            boolean restrictToWorkspace,
            Config.ExecToolConfig execConfig,
            Config.WebToolsConfig webConfig,
            ApprovalService approvals,
            SkillsLoader skills,
            SpawnWorkerService spawnWorkers
    ) {
        CoreRuntimeToolPack.register(
                tools, workspace, restrictToWorkspace, execConfig, approvals, skills, spawnWorkers
        );
        OptionalRuntimeToolPack.register(
                tools, workspace, restrictToWorkspace, execConfig, webConfig
        );
    }
}
