package ricbot.bootstrap;

import ricbot.cli.CliCommands;

/**
 * Ricbot 应用程序入口类
 */
public class RicbotApplication {
    /**
     * 主方法，启动 Ricbot 应用
     *
     * @param args 命令行参数
     * @throws Exception 异常
     */
    public static void main(String[] args) throws Exception {
        CliCommands.main(args);
    }
}
