package ricbot.tool.pack;

import ricbot.domain.security.ApprovalService;
import ricbot.infra.config.Config;
import ricbot.tool.api.BuiltinToolRegistrar;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;

/** Tools required by the durable Run/Worker execution path. */
public final class CoreRuntimeToolPack {
    private CoreRuntimeToolPack() {
    }

    public static void register(
            ToolRegistry tools,
            Path workspace,
            boolean restrictToWorkspace,
            Config.ExecToolConfig execConfig,
            ApprovalService approvals
    ) {
        Config.ExecToolConfig safeExec = execConfig != null ? execConfig : new Config.ExecToolConfig();
        Path allowedDir = BuiltinToolRegistrar.allowedDir(workspace, restrictToWorkspace, safeExec);
        ApprovalService toolApprovals = safeExec.isApprovalEnabled() ? approvals : null;

        BuiltinToolRegistrar.registerCoreFileAndSearchTools(
                tools, workspace, allowedDir, toolApprovals
        );
        if (safeExec.isEnable()) {
            BuiltinToolRegistrar.registerExecTool(
                    tools, workspace, restrictToWorkspace, safeExec, toolApprovals
            );
        }
    }
}
