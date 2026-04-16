package ricbot.infra.fs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件系统路径工具类
 */
public final class FsPathUtils {

    private FsPathUtils() {
    }

    public static Path resolvePath(
            String path,
            Path workspace,
            Path allowedDir,
            List<Path> extraAllowedDirs
    ) {
        Path p = expandUser(path);

        if (!p.isAbsolute() && workspace != null) {
            p = workspace.resolve(p);
        }

        Path resolved = p.toAbsolutePath().normalize();

        if (allowedDir != null) {
            if (!isUnder(resolved, allowedDir)
                    && (extraAllowedDirs == null
                    || extraAllowedDirs.stream().noneMatch(dir -> isUnder(resolved, dir)))) {
                throw new SecurityException(
                        "Path " + path + " is outside allowed directory " + allowedDir
                );
            }
        }

        return resolved;
    }

    public static boolean isUnder(Path path, Path directory) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedDir = directory.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedDir);
    }

    public static Path expandUser(String raw) {
        if (raw.startsWith("~")) {
            String home = System.getProperty("user.home");
            return Paths.get(home + raw.substring(1));
        }
        return Paths.get(raw);
    }
}