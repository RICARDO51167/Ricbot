package ricbot.tool.filesystem;

import ricbot.tool.api.Tool;
import ricbot.tool.api.BuiltinParameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 列出指定目录下的文件和子目录的工具类。
 */
public class ListDirTool extends ricbot.tool.api.BuiltinTool {
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
    public List<BuiltinParameter> getParams() {
        return List.of(
                BuiltinParameter.of("path", "string", "目录路径", false).defaultValue(".")
        );
    }

    @Override
    public Object execute(Map<String, Object> params) {
        Map<String, Object> safe = params != null ? params : Map.of();
        return list((String) safe.get("path"));
    }

    private String list(String path) {
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
