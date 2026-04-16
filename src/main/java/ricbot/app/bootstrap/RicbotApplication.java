package ricbot.app.bootstrap;

import ricbot.app.cli.CliCommands;

import java.nio.file.Files;
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

        String workspace = null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String cur = args[i];
                if ("--workspace".equals(cur) || "-w".equals(cur)) {
                    if (i + 1 < args.length) {
                        workspace = args[i + 1];
                    }
                }
            }
        }

        Path workspacePath;
        if (workspace == null || workspace.isBlank()) {
            workspacePath = Path.of(System.getProperty("user.dir"));
        } else {
            workspacePath = Path.of(workspace);
        }
        workspacePath = workspacePath.toAbsolutePath().normalize();

        Path logsDir = workspacePath.resolve(".ricbot").resolve("logs");
        try {
            Files.createDirectories(logsDir);
        } catch (Exception ignored) {
        }
        Path logFile = logsDir.resolve("ricbot.log");
        System.setProperty("ricbot.log.file", logFile.toString());
    }

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
