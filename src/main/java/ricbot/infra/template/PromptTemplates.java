// 定义包路径，属于 ricbot.infra.template 模块
package ricbot.infra.template;

// 导入 IOException 用于处理输入输出异常
import java.io.IOException;
// 导入 InputStream 用于读取资源流
import java.io.InputStream;
// 导入 Files 工具类，用于文件操作
import java.nio.file.Files;
// 导入 Path 类，用于表示文件路径
import java.nio.file.Path;
// 导入 StandardCharsets，指定 UTF-8 字符集
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
// 导入 Map 接口，用于存储模板参数键值对
import java.util.Map;

/**
 * 提示词模板工具类
 * 负责加载和渲染模板文件，支持从 classpath 或开发环境文件系统读取
 */
public final class PromptTemplates {

    // 定义开发环境下模板文件的根路径，指向 src/main/resources/templates 并标准化为绝对路径
    private static final Path DEV_TEMPLATES_ROOT = Path.of("src", "main", "resources", "templates")
            .toAbsolutePath()
            .normalize();

    // 私有构造函数，防止外部实例化此类
    private PromptTemplates() {
    }

    /**
     * 渲染模板的核心方法
     *
     * @param name   模板名称
     * @param strip  是否去除尾部空白字符
     * @param kwargs 模板参数映射表，key 为占位符名称，value 为替换值
     * @return 渲染后的模板字符串
     */
    public static String renderTemplate(String name, boolean strip, Map<String, Object> kwargs) {
        try {
            // 读取原始模板文本
            String text = readTemplateText(name);
            // 如果提供了参数映射，则进行占位符替换
            if (kwargs != null) {
                // 遍历参数映射中的每一个 entry
                for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
                    String k = entry.getKey();
                    String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
                    if (k == null || k.isBlank()) {
                        continue;
                    }
                    Pattern p = Pattern.compile("\\{\\{\\s*" + Pattern.quote(k) + "\\s*\\}\\}");
                    text = p.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(value));
                }
            }
            // 根据 strip 参数决定是否去除尾部空白，并返回结果
            return strip ? text.stripTrailing() : text;
        } catch (IOException e) {
            // 捕获 IO 异常并包装为运行时异常抛出，附带模板名称信息
            throw new RuntimeException("渲染模板失败: " + name, e);
        }
    }

    /**
     * 读取模板文本内容
     * 优先从 classpath 资源中读取，若不存在则尝试从开发环境文件系统读取
     *
     * @param name 模板名称
     * @return 模板文本内容
     * @throws IOException 当模板未找到或读取失败时抛出
     */
    private static String readTemplateText(String name) throws IOException {
        // 规范化模板名称，将反斜杠替换为正斜杠，若 name 为 null 则置为空串
        String normalized = name != null ? name.replace("\\", "/") : "";
        // 构造资源路径，确保以 templates/ 开头
        String resource = normalized.startsWith("templates/") ? normalized : "templates/" + normalized;

        // 尝试从 classpath 加载资源
        try (InputStream in = PromptTemplates.class.getClassLoader().getResourceAsStream(resource)) {
            // 如果资源流不为空，说明找到了 classpath 下的资源
            if (in != null) {
                // 读取所有字节并使用 UTF-8 解码为字符串
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 忽略 classpath 读取过程中的异常，继续尝试文件系统读取
        }

        // 构建开发环境下的文件路径，并解析为绝对且规范化的路径
        Path devPath = DEV_TEMPLATES_ROOT.resolve(normalized).toAbsolutePath().normalize();
        // 检查文件是否存在
        if (Files.exists(devPath)) {
            // 若存在，直接读取文件内容为字符串
            return Files.readString(devPath);
        }

        // 若两种方式都未找到模板，抛出 IOException
        throw new IOException("未找到模板: " + normalized);
    }
}
