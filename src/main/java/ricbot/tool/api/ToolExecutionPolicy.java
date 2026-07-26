package ricbot.tool.api;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

public final class ToolExecutionPolicy {
    private static final ToolExecutionPolicy DEFAULT = new ToolExecutionPolicy();
    private static final int NO_REGISTRY_TIMEOUT_SECONDS = 0;

    public static ToolExecutionPolicy defaultPolicy() {
        return DEFAULT;
    }

    public ToolRegistry.ToolPolicy policyFor(String name, Tool tool) {
        if (tool == null) {
            return new ToolRegistry.ToolPolicy(name, false, false, true, "missing");
        }
        ToolEffectPolicy effect = tool.effectPolicy();
        boolean readOnly = effect.readOnly();
        boolean exclusive = effect.concurrency() == ToolEffectPolicy.Concurrency.EXCLUSIVE_WORKSPACE;
        return new ToolRegistry.ToolPolicy(
                name,
                readOnly,
                exclusive,
                !exclusive && readOnly,
                effect.effect().name().toLowerCase(java.util.Locale.ROOT)
        );
    }

    public boolean canRunConcurrently(
            Collection<String> names,
            Function<String, ToolRegistry.ToolPolicy> policyLookup
    ) {
        if (names == null || names.isEmpty()) {
            return true;
        }
        for (String name : names) {
            ToolRegistry.ToolPolicy policy = policyLookup != null
                    ? policyLookup.apply(name)
                    : policyFor(name, null);
            if (!policy.concurrentSafe()) {
                return false;
            }
        }
        return true;
    }

    public boolean shouldRunConcurrently(
            boolean requestedConcurrent,
            Collection<String> names,
            Function<String, ToolRegistry.ToolPolicy> policyLookup
    ) {
        return requestedConcurrent && canRunConcurrently(names, policyLookup);
    }

    public int timeoutSecondsFor(ToolRegistry.ToolPolicy policy) {
        return NO_REGISTRY_TIMEOUT_SECONDS;
    }

    public boolean shouldStopRunOnToolError(Map<String, Object> firstError, boolean failOnToolError) {
        return failOnToolError && firstError != null;
    }

    public int maxToolResultChars(int configuredMaxChars) {
        return configuredMaxChars;
    }
}
