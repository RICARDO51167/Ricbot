package ricbot.tool.filesystem;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 列出指定目录下的文件和子目录的工具类。
 */
public class ListDirTool extends Tool {
    @Override public ricbot.tool.api.ToolEffectPolicy effectPolicy() {
        return ricbot.tool.api.ToolEffectPolicy.readOnly(java.time.Duration.ofSeconds(30));
    }

    private final Path workspace;

    private final Path allowedDir;

    public ListDirTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getName() {
        return "list_dir";
    }

    @Override
    public String getDescription() {
        return "列出某个目录下的文件与子目录。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "目录路径", false).setDefaultValue(".")
        );
    }

    public String execute(String path) {
        try {
            String targetPath = (path == null || path.isBlank()) ? "." : path;
            Path dir = FileToolSupport.resolvePath(workspace, targetPath);
            FileToolSupport.ensureAllowed(dir, allowedDir, List.of());

            if (!Files.exists(dir)) {
                return "错误：目录不存在：" + dir;
            }
            if (!Files.isDirectory(dir)) {
                return "错误：该路径不是目录：" + dir;
            }

            List<Path> items = FileToolSupport.listDir(dir);
            return FileToolSupport.formatDirList(dir, items);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}
