package ricbot.tool.filesystem;

import ricbot.tool.api.Tool;
import ricbot.infra.fs.FsPathUtils;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件系统工具抽象基类。
 * <p>
 * 提供工作空间路径解析和安全访问控制功能，确保所有文件操作都在允许的目录范围内进行。
 */
public abstract class FsTool extends Tool {

    /**
     * 工作空间根路径。
     */
    protected final Path workspace;

    /**
     * 允许访问的主目录路径。
     */
    protected final Path allowedDir;

    /**
     * 额外允许访问的目录列表。
     */
    protected final List<Path> extraAllowedDirs;

    /**
     * 构造函数。
     *
     * @param workspace       工作空间根路径
     * @param allowedDir      允许访问的主目录路径
     * @param extraAllowedDirs 额外允许访问的目录列表
     */
    protected FsTool(Path workspace, Path allowedDir, List<Path> extraAllowedDirs) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.extraAllowedDirs = extraAllowedDirs;
    }

    /**
     * 解析给定的相对或绝对路径，确保其在安全访问范围内。
     *
     * @param path 待解析的路径字符串
     * @return 解析后的安全 {@link Path} 对象
     */
    protected Path resolve(String path) {
        return FsPathUtils.resolvePath(path, workspace, allowedDir, extraAllowedDirs);
    }
}