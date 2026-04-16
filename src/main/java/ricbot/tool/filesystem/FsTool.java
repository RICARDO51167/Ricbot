package ricbot.tool.filesystem;

import ricbot.tool.api.Tool;
import ricbot.infra.fs.FsPathUtils;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件系统工具抽象基类
 */
public abstract class FsTool extends Tool {

    protected final Path workspace;

    protected final Path allowedDir;

    protected final List<Path> extraAllowedDirs;

    protected FsTool(Path workspace, Path allowedDir, List<Path> extraAllowedDirs) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.extraAllowedDirs = extraAllowedDirs;
    }

    protected Path resolve(String path) {
        return FsPathUtils.resolvePath(path, workspace, allowedDir, extraAllowedDirs);
    }
}