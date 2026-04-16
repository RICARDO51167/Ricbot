package ricbot.tool.filesystem;


import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 列出指定目录下的文件和子目录的工具类。
 * <p>
 * 对应 Python: ListDirTool
 * <p>
 * 主要功能：
 * 1. 列出目录内容
 * 2. 目录优先、文件其次排序
 * 3. 显示文件大小
 */
public class ListDirTool extends Tool {

    /**
     * 工作空间根路径
     */
    private final Path workspace;

    /**
     * 允许访问的目录路径，用于安全校验
     */
    private final Path allowedDir;

    /**
     * 构造函数
     *
     * @param workspace 工作空间根路径
     * @param allowedDir 允许访问的目录路径，如果为 null 则不进行特定目录限制
     */
    public ListDirTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称 "list_dir"
     */
    @Override
    public String getName() {
        return "list_dir";
    }

    /**
     * 获取工具描述
     *
     * @return 工具描述信息
     */
    @Override
    public String getDescription() {
        return "列出某个目录下的文件与子目录。";
    }

    /**
     * 判断该工具是否为只读操作
     *
     * @return true，因为列出目录内容不修改文件系统
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 获取工具参数定义
     *
     * @return 参数列表，包含一个可选的 "path" 参数，默认为当前目录 "."
     */
    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "目录路径", false).setDefaultValue(".")
        );
    }

    /**
     * 执行列出目录内容的操作
     *
     * @param path 要列出的目录路径，如果为空或 null 则默认为当前目录 "."
     * @return 格式化后的目录列表字符串，或者错误信息
     */
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
