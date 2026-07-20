package ricbot.infra.execution;

import ricbot.infra.config.Config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Builds and probes the configured command execution backend. */
public final class ExecutionBackendFactory {
    private ExecutionBackendFactory() {
    }

    public static ExecutionBackend create(Config.ExecToolConfig config) {
        Config.ExecToolConfig safe = config != null ? config : new Config.ExecToolConfig();
        ExecutionBackendRegistry registry = new ExecutionBackendRegistry()
                .register(new LocalExecutionBackend())
                .register(new DockerExecutionBackend(safe.getDockerImage(), safe.isDockerNetworkEnabled()));
        String preferred = clean(safe.getBackend(), "local");
        String fallback = clean(safe.getFallbackBackend(), "local");
        ExecutionBackendRegistry.Selection selection = registry.select(
                preferred, fallback, safe.isAllowBackendFallback());
        return new SelectedBackend(selection.backend(), preferred, selection.fallbackUsed(), selection.reason());
    }

    private static String clean(String value, String fallback) {
        String clean = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        return clean.isBlank() ? fallback : clean;
    }

    /** Adds selection/fallback evidence to every execution result. */
    private record SelectedBackend(ExecutionBackend delegate, String preferred,
                                   boolean fallbackUsed, String fallbackReason) implements ExecutionBackend {
        private SelectedBackend {
            java.util.Objects.requireNonNull(delegate, "delegate");
            preferred = preferred != null ? preferred : delegate.name();
            fallbackReason = fallbackReason != null ? fallbackReason : "";
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public ExecutionCapabilities probe() {
            return delegate.probe();
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request) throws Exception {
            ExecutionResult result = delegate.execute(request);
            Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
            metadata.put("preferred_backend", preferred);
            metadata.put("selected_backend", delegate.name());
            metadata.put("fallback_used", fallbackUsed);
            if (fallbackUsed) metadata.put("fallback_reason", fallbackReason);
            return new ExecutionResult(result.exitCode(), result.stdout(), result.stderr(), result.timedOut(),
                    result.truncated(), result.duration() != null ? result.duration() : Duration.ZERO,
                    result.backend(), metadata);
        }
    }
}
