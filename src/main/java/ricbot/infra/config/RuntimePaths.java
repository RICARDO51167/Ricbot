package ricbot.infra.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 对应 Python: paths.py / runtime path helpers
 */
public final class RuntimePaths {

    // 私有构造函数，防止实例化
    private RuntimePaths() {
    }

    /**
     * 获取主数据目录 (~/.ricbot)
     * @return 数据目录路径
     */
    public static Path getDataDir() {
        // 构建用户主目录下的 .ricbot 路径
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot");
        // 确保目录存在
        ensureDir(dir);
        return dir;
    }

    /**
     * 获取运行时子目录
     * @param name 子目录名称
     * @return 子目录路径
     */
    public static Path getRuntimeSubdir(String name) {
        // 在主数据目录下解析子目录名称
        Path dir = getDataDir().resolve(name);
        // 确保目录存在
        ensureDir(dir);
        return dir;
    }

    /**
     * 获取媒体目录 (~/.ricbot/media)
     * @return 媒体目录路径
     */
    public static Path getMediaDir() {
        return getRuntimeSubdir("media");
    }

    /**
     * 获取指定频道的媒体目录
     * @param channel 频道名称
     * @return 频道媒体目录路径
     */
    public static Path getMediaDir(String channel) {
        // 如果频道为空或空白，返回默认媒体目录
        if (channel == null || channel.isBlank()) {
            return getMediaDir();
        }
        // 在默认媒体目录下解析频道名称
        Path dir = getMediaDir().resolve(channel);
        // 确保目录存在
        ensureDir(dir);
        return dir;
    }

    /**
     * 获取定时任务目录 (~/.ricbot/cron)
     * @return 定时任务目录路径
     */
    public static Path getCronDir() {
        return getRuntimeSubdir("cron");
    }

    /**
     * 获取日志目录 (~/.ricbot/logs)
     * @return 日志目录路径
     */
    public static Path getLogsDir() {
        return getRuntimeSubdir("logs");
    }

    /**
     * 获取工作区路径
     * @param workspace 工作区路径字符串，如果为空则使用默认工作区
     * @return 规范化后的绝对工作区路径
     */
    public static Path getWorkspacePath(String workspace) {
        Path path;
        // 判断工作区参数是否为空
        if (workspace == null || workspace.isBlank()) {
            // 使用默认工作区路径 ~/.ricbot/workspace
            path = Path.of(System.getProperty("user.home"), ".ricbot", "workspace");
        } else {
            // 使用指定的工作区路径
            path = Path.of(workspace);
        }
        // 确保目录存在
        ensureDir(path);
        // 返回规范化后的绝对路径
        return path.toAbsolutePath().normalize();
    }

    /**
     * 判断给定工作区是否为默认工作区
     * @param workspace 工作区路径字符串
     * @return 如果是默认工作区返回 true，否则返回 false
     */
    public static boolean isDefaultWorkspace(String workspace) {
        // 获取当前工作区路径
        Path current = getWorkspacePath(workspace);
        // 获取默认工作区路径
        Path def = getWorkspacePath(null);
        // 比较两者是否相等
        return current.equals(def);
    }

    /**
     * 获取 CLI 历史记录文件路径 (~/.ricbot/history/cli_history)
     * @return CLI 历史记录文件路径
     */
    public static Path getCliHistoryPath() {
        // 构建历史目录路径
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "history");
        // 确保目录存在
        ensureDir(dir);
        // 返回历史记录文件路径
        return dir.resolve("cli_history");
    }

    /**
     * 获取 Bridge 安装目录 (~/.ricbot/bridge)
     * @return Bridge 安装目录路径
     */
    public static Path getBridgeInstallDir() {
        // 构建 Bridge 目录路径
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "bridge");
        // 确保目录存在
        ensureDir(dir);
        return dir;
    }

    /**
     * 获取旧版会话目录 (~/.ricbot/sessions)
     * @return 旧版会话目录路径
     */
    public static Path getLegacySessionsDir() {
        // 构建会话目录路径
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "sessions");
        // 确保目录存在
        ensureDir(dir);
        return dir;
    }

    /**
     * 确保指定路径的目录存在，如果不存在则创建
     * @param path 目录路径
     * @throws RuntimeException 如果创建目录失败
     */
    private static void ensureDir(Path path) {
        try {
            // 创建所有必需的父目录
            Files.createDirectories(path);
        } catch (Exception e) {
            // 抛出运行时异常，包含路径信息
            throw new RuntimeException("创建目录失败：" + path, e);
        }
    }
}
