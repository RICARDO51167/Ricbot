package ricbot.tool.api;

import ricbot.infra.config.Config;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.infra.execution.ExecutionBackend;
import ricbot.infra.execution.ExecutionBackendFactory;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
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
        registerFileAndSearchTools(registry, workspace, allowedDir, null);
    }

    public static void registerFileAndSearchTools(ToolRegistry registry, Path workspace, Path allowedDir, ApprovalService approvalService) {
        registerCoreFileAndSearchTools(registry, workspace, allowedDir, approvalService);
    }

    public static void registerCoreFileAndSearchTools(
            ToolRegistry registry,
            Path workspace,
            Path allowedDir,
            ApprovalService approvalService
    ) {
        CommandRiskAnalyzer riskAnalyzer = approvalService != null ? new CommandRiskAnalyzer(workspace) : null;
        registry.register(new ReadFileTool(workspace, allowedDir, List.of()));
        registry.register(new ListDirTool(workspace, allowedDir));
        registry.register(new WriteFileTool(workspace, allowedDir, riskAnalyzer, approvalService));
        registry.register(new EditFileTool(workspace, allowedDir, riskAnalyzer, approvalService));
        registry.register(new GlobTool(workspace, allowedDir));
        registry.register(new GrepTool(workspace, allowedDir));
    }

    public static void registerExecTool(
            ToolRegistry registry,
            Path workspace,
            boolean restrictToWorkspace,
            Config.ExecToolConfig execConfig
    ) {
        registerExecTool(registry, workspace, restrictToWorkspace, execConfig, null);
    }

    public static void registerExecTool(
            ToolRegistry registry,
            Path workspace,
            boolean restrictToWorkspace,
            Config.ExecToolConfig execConfig,
            ApprovalService approvalService
    ) {
        if (execConfig == null || !execConfig.isEnable()) {
            return;
        }
        ExecutionBackend backend = ExecutionBackendFactory.create(execConfig);
        registry.register(new ExecTool(
                execConfig.getTimeout(),
                workspace.toString(),
                null,
                null,
                restrictToWorkspace,
                execConfig.isSandbox() && "local".equals(backend.name()) ? "sandbox" : "",
                execConfig.getPathAppend(),
                execConfig.getAllowedEnvKeys(),
                approvalService != null ? new CommandRiskAnalyzer(workspace) : null,
                approvalService,
                backend
        ));
    }
}
