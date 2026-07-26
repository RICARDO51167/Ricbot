package ricbot.tool.pack;

import ricbot.domain.security.ApprovalService;
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
            ApprovalService approvals
    ) {
        CoreRuntimeToolPack.register(
                tools, workspace, restrictToWorkspace, execConfig, approvals
        );
    }
}
