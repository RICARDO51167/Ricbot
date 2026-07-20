package ricbot.infra.config;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimePaths {

    private RuntimePaths() {
    }

    /**
     * 获取主数据目录 (~/.ricbot)
     * @return 数据目录路径
     */
    public static Path getDataDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot");
        ensureDir(dir);
        return dir;
    }

    /**
     * 获取运行时子目录
     * @param name 子目录名称
     * @return 子目录路径
     */
    public static Path getRuntimeSubdir(String name) {
        Path dir = getDataDir().resolve(name);
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
        if (channel == null || channel.isBlank()) {
            return getMediaDir();
        }
        Path dir = getMediaDir().resolve(channel);
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
        return resolveWorkspacePath(
                workspace,
                Path.of(System.getProperty("user.home"), ".ricbot", "workspace")
        );
    }

    public static Path configureWorkspaceLogFile(String workspace, Path defaultWorkspace) {
        Path workspacePath = normalizeWorkspacePath(
                workspace,
                defaultWorkspace != null
                        ? defaultWorkspace
                        : Path.of(System.getProperty("user.home"), ".ricbot", "workspace")
        );
        Path logsDir = workspacePath.resolve(".ricbot").resolve("logs");
        try {
            Files.createDirectories(logsDir);
        } catch (Exception ignored) {
        }
        Path logFile = logsDir.resolve("ricbot.log");
        System.setProperty("ricbot.log.file", logFile.toString());
        return logFile;
    }

    public static String workspaceOption(String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String cur = args[i];
            if (("--workspace".equals(cur) || "-w".equals(cur)) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static Path resolveWorkspacePath(String workspace, Path defaultWorkspace) {
        Path path = normalizeWorkspacePath(workspace, defaultWorkspace);
        ensureDir(path);
        return path;
    }

    private static Path normalizeWorkspacePath(String workspace, Path defaultWorkspace) {
        Path path = workspace == null || workspace.isBlank() ? defaultWorkspace : Path.of(workspace);
        return path.toAbsolutePath().normalize();
    }

    /**
     * 判断给定工作区是否为默认工作区
     * @param workspace 工作区路径字符串
     * @return 如果是默认工作区返回 true，否则返回 false
     */
    public static boolean isDefaultWorkspace(String workspace) {
        Path current = getWorkspacePath(workspace);
        Path def = getWorkspacePath(null);
        return current.equals(def);
    }

    /**
     * 获取 CLI 历史记录文件路径 (~/.ricbot/history/cli_history)
     * @return CLI 历史记录文件路径
     */
    public static Path getCliHistoryPath() {
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "history");
        ensureDir(dir);
        return dir.resolve("cli_history");
    }

    /**
     * 获取 Bridge 安装目录 (~/.ricbot/bridge)
     * @return Bridge 安装目录路径
     */
    public static Path getBridgeInstallDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "bridge");
        ensureDir(dir);
        return dir;
    }

    /**
     * 获取旧版会话目录 (~/.ricbot/sessions)
     * @return 旧版会话目录路径
     */
    public static Path getLegacySessionsDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "sessions");
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
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException("创建目录失败：" + path, e);
        }
    }
}
