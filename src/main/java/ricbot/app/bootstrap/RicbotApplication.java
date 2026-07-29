package ricbot.app.bootstrap;

import ricbot.app.cli.CliCommands;
import ricbot.infra.config.RuntimePaths;

import java.nio.file.Path;

/**
 * Ricbot 应用程序入口类
 */
public class RicbotApplication {

    /**
     * 引导日志配置
     * 检查是否已设置日志文件属性，若未设置则根据工作区选项和当前目录配置默认日志路径。
     */
    private static void bootstrapLogging(String[] args) {
        String existing = System.getProperty("ricbot.log.file");
        if (existing != null && !existing.isBlank()) {
            return;
        }

        RuntimePaths.configureWorkspaceLogFile(
                RuntimePaths.workspaceOption(args),
                Path.of(System.getProperty("user.dir"))
        );
    }

    /**
     * 程序主入口
     * 1. 初始化日志系统
     * 2. 执行 CLI 命令
     * 3. 捕获并处理异常：优先使用 SLF4J 记录错误，其次输出到控制台并提示查看日志文件
     */
    public static void main(String[] args) {
        bootstrapLogging(args);
        try {
            CliCommands.main(args);
        } catch (Throwable t) {
            // 尝试使用日志框架记录错误（防止日志系统本身尚未就绪）
            try {
                org.slf4j.LoggerFactory.getLogger(RicbotApplication.class).error("ricbot 启动失败", t);
            } catch (Throwable ignored) {
                // 忽略日志框架初始化失败的异常
            }
            
            // 确定日志文件路径用于用户提示
            String logFile = System.getProperty("ricbot.log.file");
            if (logFile == null || logFile.isBlank()) {
                logFile = System.getProperty("user.home") + "/.ricbot/logs/ricbot.log";
            }
            
            // 向标准错误流输出友好的错误信息
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) {
                System.err.println("ricbot: " + msg);
            }
            System.err.println("ricbot: 发生错误，详情见日志文件: " + logFile);
            System.exit(1);
        }
    }
}
