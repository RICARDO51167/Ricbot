package ricbot.tool.api;

import ricbot.infra.config.Config;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.note.NoteTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.rag.RagTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;

import java.nio.file.Path;
import java.util.List;

public final class BuiltinToolRegistrar {

    private BuiltinToolRegistrar() {
    }

    public static Path allowedDir(Path workspace, boolean restrictToWorkspace, Config.ExecToolConfig execConfig) {
        return restrictToWorkspace || (execConfig != null && execConfig.isSandbox()) ? workspace : null;
    }

    public static void registerFileAndSearchTools(ToolRegistry registry, Path workspace, Path allowedDir) {
        registry.register(new ReadFileTool(workspace, allowedDir, List.of()));
        registry.register(new ListDirTool(workspace, allowedDir));
        registry.register(new WriteFileTool(workspace, allowedDir));
        registry.register(new EditFileTool(workspace, allowedDir));
        registry.register(new GlobTool(workspace, allowedDir));
        registry.register(new GrepTool(workspace, allowedDir));
        registry.register(new NoteTool(workspace));
        registry.register(new RagTool(workspace));
    }

    public static void registerExecTool(
            ToolRegistry registry,
            Path workspace,
            boolean restrictToWorkspace,
            Config.ExecToolConfig execConfig
    ) {
        if (execConfig == null || !execConfig.isEnable()) {
            return;
        }
        registry.register(new ExecTool(
                execConfig.getTimeout(),
                workspace.toString(),
                null,
                null,
                restrictToWorkspace,
                execConfig.isSandbox() ? "sandbox" : "",
                execConfig.getPathAppend(),
                execConfig.getAllowedEnvKeys()
        ));
    }
}
