package ricbot.tool.pack;

import ricbot.infra.config.Config;
import ricbot.tool.api.BuiltinToolRegistrar;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.NotebookEditTool;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;

import java.nio.file.Path;
import java.util.List;

/** Optional workspace knowledge, notebook, and web adapters. MCP is loaded separately by MCPLoader. */
public final class OptionalRuntimeToolPack {
    private OptionalRuntimeToolPack() {
    }

    public static void register(
            ToolRegistry tools,
            Path workspace,
            boolean restrictToWorkspace,
            Config.ExecToolConfig execConfig,
            Config.WebToolsConfig webConfig
    ) {
        Config.ExecToolConfig safeExec = execConfig != null ? execConfig : new Config.ExecToolConfig();
        Path allowedDir = BuiltinToolRegistrar.allowedDir(workspace, restrictToWorkspace, safeExec);

        BuiltinToolRegistrar.registerKnowledgeTools(tools, workspace);
        tools.register(new NotebookEditTool(workspace, allowedDir, List.of()));

        if (webConfig != null && webConfig.isEnable()) {
            tools.register(new WebFetchTool(webConfig.getMaxChars(), webConfig.getProxy()));
            tools.register(new WebSearchTool(webConfig.getSearch(), webConfig.getProxy()));
        }
    }
}
