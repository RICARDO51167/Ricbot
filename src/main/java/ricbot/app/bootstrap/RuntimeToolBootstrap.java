package ricbot.app.bootstrap;

import ricbot.domain.agent.SpawnWorkerService;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.skill.SkillsLoader;
import ricbot.infra.config.Config;
import ricbot.tool.api.BuiltinToolRegistrar;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.NotebookEditTool;
import ricbot.tool.process.SpawnTool;
import ricbot.tool.skill.ReadSkillTool;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;

import java.nio.file.Path;
import java.util.List;

/** Bootstrap-owned registration of the built-in runtime tool pack. */
public final class RuntimeToolBootstrap {
    private RuntimeToolBootstrap() {
    }

    public static void register(ToolRegistry tools, Path workspace, boolean restrictToWorkspace,
                                Config.ExecToolConfig execConfig, Config.WebToolsConfig webConfig,
                                ApprovalService approvals, SkillsLoader skills, SpawnWorkerService spawnWorkers) {
        Path allowedDir = BuiltinToolRegistrar.allowedDir(workspace, restrictToWorkspace, execConfig);
        ApprovalService toolApprovals = execConfig != null && execConfig.isApprovalEnabled() ? approvals : null;

        tools.register(new ReadSkillTool(skills));
        BuiltinToolRegistrar.registerFileAndSearchTools(tools, workspace, allowedDir, toolApprovals);
        tools.register(new NotebookEditTool(workspace, allowedDir, List.of()));

        if (execConfig.isEnable()) {
            BuiltinToolRegistrar.registerExecTool(tools, workspace, restrictToWorkspace, execConfig, toolApprovals);
            tools.register(new SpawnTool(spawnWorkers));
        }
        if (webConfig.isEnable()) {
            tools.register(new WebFetchTool(webConfig.getMaxChars(), webConfig.getProxy()));
            tools.register(new WebSearchTool(webConfig.getSearch(), webConfig.getProxy()));
        }
    }
}
