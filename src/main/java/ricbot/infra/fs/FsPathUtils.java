package ricbot.infra.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件系统路径工具类，提供路径解析、安全校验及用户目录扩展等功能。
 */
public final class FsPathUtils {

    // 私有构造函数，防止实例化
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
        // 第一步：扩展用户主目录符号 "~"
        Path p = expandUser(path);

        // 第二步：如果路径不是绝对路径且提供了工作空间，则基于工作空间解析
        if (!p.isAbsolute() && workspace != null) {
            p = workspace.resolve(p);
        }

        // 第三步：转换为绝对路径并进行标准化处理（消除 "." 和 ".."）
        Path resolved = p.toAbsolutePath().normalize();

        // 第四步：安全检查，验证路径是否在允许的目录范围内
        validateAccess(resolved, allowedDir, extraAllowedDirs, true);

        // 返回解析并校验后的路径
        return resolved;
    }

    /**
     * 校验路径是否在允许目录内。
     * 读取现有路径时会解析符号链接；创建新文件时会对父目录做真实路径校验，
     * 以阻止通过工作区内的 symlink 越界访问。
     */
    public static void validateAccess(
            Path path,
            Path allowedDir,
            List<Path> extraAllowedDirs,
            boolean allowMissingLeaf
    ) {
        if (allowedDir == null && (extraAllowedDirs == null || extraAllowedDirs.isEmpty())) {
            return;
        }

        List<Path> allowedRoots = new ArrayList<>();
        if (allowedDir != null) {
            allowedRoots.add(allowedDir);
        }
        if (extraAllowedDirs != null) {
            allowedRoots.addAll(extraAllowedDirs);
        }

        for (Path root : allowedRoots) {
            if (root != null && isUnder(path, root, allowMissingLeaf)) {
                return;
            }
        }

        throw new SecurityException("Path " + path + " is outside allowed directories");
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
        return isUnder(path, directory, false);
    }

    /**
     * 判断路径是否位于指定目录下。
     * allowMissingLeaf=true 时允许最后一个路径元素不存在，并对其父目录做真实路径校验。
     */
    public static boolean isUnder(Path path, Path directory, boolean allowMissingLeaf) {
        try {
            Path normalizedDir = resolveForComparison(directory, false);
            Path normalizedPath = resolveForComparison(path, allowMissingLeaf);
            return normalizedPath.equals(normalizedDir) || normalizedPath.startsWith(normalizedDir);
        } catch (IOException e) {
            return false;
        }
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
        // 检查路径是否以 "~" 开头
        if (raw.startsWith("~")) {
            // 获取系统用户主目录
            String home = System.getProperty("user.home");
            // 拼接用户主目录和剩余路径部分
            return Paths.get(home + raw.substring(1));
        }
        // 如果不是以 "~" 开头，直接创建路径对象
        return Paths.get(raw);
    }

    private static Path resolveForComparison(Path path, boolean allowMissingLeaf) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!allowMissingLeaf || Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return normalized.toRealPath();
        }

        Path parent = normalized.getParent();
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            parent = parent.getParent();
        }
        if (parent == null) {
            return normalized;
        }
        Path realParent = parent.toRealPath();
        return realParent.resolve(parent.relativize(normalized)).normalize();
    }
}
