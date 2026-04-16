package ricbot.infra.template;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 提示词模板工具类
 */
public final class PromptTemplates {

    private static final Path DEV_TEMPLATES_ROOT = Path.of("src", "main", "resources", "templates")
            .toAbsolutePath()
            .normalize();

    private PromptTemplates() {
    }

    public static String renderTemplate(String name, boolean strip, Map<String, Object> kwargs) {
        try {
            String text = readTemplateText(name);
            if (kwargs != null) {
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
            return strip ? text.stripTrailing() : text;
        } catch (IOException e) {
            throw new RuntimeException("渲染模板失败: " + name, e);
        }
    }

    private static String readTemplateText(String name) throws IOException {
        String normalized = name != null ? name.replace("\\", "/") : "";
        String resource = normalized.startsWith("templates/") ? normalized : "templates/" + normalized;

        try (InputStream in = PromptTemplates.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }

        Path devPath = DEV_TEMPLATES_ROOT.resolve(normalized).toAbsolutePath().normalize();
        if (Files.exists(devPath)) {
            return Files.readString(devPath);
        }

        throw new IOException("未找到模板: " + normalized);
    }
}
