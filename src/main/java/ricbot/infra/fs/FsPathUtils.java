package ricbot.infra.fs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件系统路径工具类，提供路径解析、安全校验及用户目录扩展等功能。
 */
public final class FsPathUtils {

    private FsPathUtils() {
    }

    /**
     * 解析并校验文件路径。
     * <p>
     * 处理逻辑如下：
     * 1. 支持将 "~" 扩展为用户主目录。
     * 2. 如果路径是相对路径且提供了工作空间（workspace），则基于工作空间解析为绝对路径。
     * 3. 对路径进行标准化处理（去除 "." 和 ".." 等）。
     * 4. 安全检查：如果指定了允许的主目录（allowedDir），则校验解析后的路径是否位于该目录或其子目录下；
     *    如果不在主目录下，则检查是否在额外允许的目录列表（extraAllowedDirs）中。
     *    若均不满足，则抛出 SecurityException。
     *
     * @param path             待解析的路径字符串
     * @param workspace        工作空间路径，用于解析相对路径
     * @param allowedDir       允许访问的主目录
     * @param extraAllowedDirs 额外允许访问的目录列表
     * @return 解析并校验后的绝对路径
     * @throws SecurityException 当路径不在允许的目录范围内时抛出
     */
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

    /**
     * 判断给定路径是否位于指定目录下（包括子目录）。
     * <p>
     * 该方法会对两个路径进行绝对化和标准化处理后进行比较。
     *
     * @param path      待检查的路径
     * @param directory 基准目录
     * @return 如果 path 位于 directory 下则返回 true，否则返回 false
     */
    public static boolean isUnder(Path path, Path directory) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedDir = directory.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedDir);
    }

    /**
     * 扩展用户主目录符号 "~"。
     * <p>
     * 如果输入路径以 "~" 开头，则将其替换为当前用户的家目录（user.home）。
     * 例如："~/docs" 会被转换为 "/home/username/docs"（Linux/Mac）或 "C:\Users\\username\docs"（Windows）。
     *
     * @param raw 原始路径字符串
     * @return 扩展后的路径对象
     */
    public static Path expandUser(String raw) {
        if (raw.startsWith("~")) {
            String home = System.getProperty("user.home");
            return Paths.get(home + raw.substring(1));
        }
        return Paths.get(raw);
    }
}