package ricbot.infra.template;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class PromptTemplates {

    private static final Path DEV_TEMPLATES_ROOT = Path.of("src", "main", "resources", "templates")
            .toAbsolutePath()
            .normalize();

    private PromptTemplates() {
    }

    public static String renderTemplate(String name) {
        return renderTemplate(name, false, Map.of());
    }

    public static String renderTemplate(String name, boolean strip, Map<String, Object> kwargs) {
        try {
            String text = readTemplateText(name);
            if (kwargs != null) {
                for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
                    String key = "{{" + entry.getKey() + "}}";
                    String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
                    text = text.replace(key, value);
                }
            }
            return strip ? text.stripTrailing() : text;
        } catch (IOException e) {
            throw new RuntimeException("Failed to render template: " + name, e);
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

        throw new IOException("Template not found: " + normalized);
    }
}
