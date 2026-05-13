package ricbot.app.bootstrap;

import ricbot.app.cli.CliCommands;
import ricbot.infra.config.RuntimePaths;

import java.nio.file.Path;

/**
 * Ricbot 应用程序入口类
 */
public class RicbotApplication {

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
     * 主方法，启动 Ricbot 应用
     *
     * @param args 命令行参数
     * @throws Exception 异常
     */
    public static void main(String[] args) {
        bootstrapLogging(args);
        try {
            CliCommands.main(args);
        } catch (Throwable t) {
            try {
                org.slf4j.LoggerFactory.getLogger(RicbotApplication.class).error("ricbot 启动失败", t);
            } catch (Throwable ignored) {
            }
            String logFile = System.getProperty("ricbot.log.file");
            if (logFile == null || logFile.isBlank()) {
                logFile = System.getProperty("user.home") + "/.ricbot/logs/ricbot.log";
            }
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) {
                System.err.println("ricbot: " + msg);
            }
            System.err.println("ricbot: 发生错误，详情见日志文件: " + logFile);
            System.exit(1);
        }
    }
}
