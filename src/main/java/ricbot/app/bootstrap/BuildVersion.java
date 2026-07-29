package ricbot.app.bootstrap;

import java.io.InputStream;
import java.util.Properties;

/** Maven 项目版本，投射到清单、CLI 和遥测数据中。
 * @author rcd*/
public final class BuildVersion {
    private BuildVersion() { }

    public static String current() {
        // 首先尝试从 JAR 包的实现版本中获取
        String implementation = BuildVersion.class.getPackage().getImplementationVersion();
        if (implementation != null && !implementation.isBlank()) return implementation.trim();
        
        // 如果未找到，则尝试从资源文件中读取
        try (InputStream input = BuildVersion.class.getResourceAsStream("/ricbot-version.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                String version = properties.getProperty("version", "").trim();
                // 确保版本不为空且不是 Maven 占位符格式
                if (!version.isBlank() && !version.startsWith("${")) return version;
            }
        } catch (Exception ignored) { 
            // 忽略加载失败的情况
        }
        
        // 默认返回开发版本标识
        return "dev";
    }
}
