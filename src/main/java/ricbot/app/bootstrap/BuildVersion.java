package ricbot.app.bootstrap;

import java.io.InputStream;
import java.util.Properties;

/** Maven project version projected into the manifest, CLI and telemetry. */
public final class BuildVersion {
    private BuildVersion() { }

    public static String current() {
        String implementation = BuildVersion.class.getPackage().getImplementationVersion();
        if (implementation != null && !implementation.isBlank()) return implementation.trim();
        try (InputStream input = BuildVersion.class.getResourceAsStream("/ricbot-version.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                String version = properties.getProperty("version", "").trim();
                if (!version.isBlank() && !version.startsWith("${")) return version;
            }
        } catch (Exception ignored) { }
        return "dev";
    }
}
