package ricbot.infra.config;

import java.nio.file.Path;
import java.util.Map;

/** Configuration plus non-secret provenance for diagnostics. */
public record ConfigLoadResult(Config config, Path path, ConfigSource source,
                               Map<String, ConfigSource> settingSources) {
    public ConfigLoadResult {
        if (config == null) throw new IllegalArgumentException("config is required");
        if (path == null) throw new IllegalArgumentException("path is required");
        source = source != null ? source : ConfigSource.DEFAULT;
        settingSources = Map.copyOf(settingSources != null ? settingSources : Map.of());
    }
}
