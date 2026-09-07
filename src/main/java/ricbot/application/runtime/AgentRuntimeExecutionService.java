package ricbot.application.runtime;

import ricbot.domain.agent.AgentRunResult;
import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.interfacep.AgentInvocationRuntime;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.domain.config.ModelCard;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.runtime.*;
import ricbot.infra.config.Config;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adapts the message-facing invocation contract to an ordinary durable v6 Run. */
public final class AgentRuntimeExecutionService implements AgentInvocationRuntime {
    private final DurableAgentRuntime runtime;
    private final TranscriptPort transcripts;
    private final AgentPhaseExecutor phases;
    private final BudgetPolicy defaultBudgetPolicy;
    private final Config.ContextOffloadConfig defaultOffload;
    private final String defaultTimezone;
    private final ModelCard.Pricing defaultPricing;

    public AgentRuntimeExecutionService(DurableAgentRuntime runtime, TranscriptPort transcripts,
                                        AgentPhaseExecutor phases) {
        this(runtime, transcripts, phases, BudgetPolicy.unlimited(),
                new Config.ContextOffloadConfig(), "UTC", null);
    }

    public AgentRuntimeExecutionService(DurableAgentRuntime runtime, TranscriptPort transcripts,
                                        AgentPhaseExecutor phases, BudgetPolicy defaultBudgetPolicy,
                                        Config.ContextOffloadConfig defaultOffload, String defaultTimezone,
                                        ModelCard.Pricing defaultPricing) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.transcripts = java.util.Objects.requireNonNull(transcripts, "transcripts");
        this.phases = java.util.Objects.requireNonNull(phases, "phases");
        this.defaultBudgetPolicy = defaultBudgetPolicy != null ? defaultBudgetPolicy : BudgetPolicy.unlimited();
        this.defaultOffload = defaultOffload != null ? defaultOffload : new Config.ContextOffloadConfig();
        this.defaultTimezone = clean(defaultTimezone).isBlank() ? "UTC" : clean(defaultTimezone);
        this.defaultPricing = defaultPricing;
    }

    @Override public AgentRunResult run(AgentRunSpec spec) {
        String runId = spec.getRunId() != null && !spec.getRunId().isBlank()
                ? spec.getRunId().trim() : "run-" + UUID.randomUUID();
        Instant started = Instant.now();
        transcripts.initialize(runId, copyMessages(spec.getInitialMessages()));
        phases.attach(runId, spec);
        try {
            RunView view = runtime.start(new RunSpec(runId, parent(spec), root(spec, runId), retryOf(spec),
                    dependencies(spec), goal(spec), "default", Math.max(12, spec.getMaxIterations() * 4),
                    metadata(spec, runId)));
            RunState state = view.state();
            String content = resultContent(state);
            if (spec.getHook() != null) {
                content = spec.getHook().finalizeContent(new AgentHookContext()
                        .setMessages(transcripts.read(runId))
                        .setIteration(number(state.channels().get("iterations")))
                        .setSessionKey(spec.getSessionKey()), content);
            }
            AgentPhaseExecutor.InvocationObservation observation = phases.observation(runId);
            UsageLedger ledger = UsageLedger.from(state.channels().get("usageLedger"));
            AgentRunResult result = new AgentRunResult().setRunId(runId).setStartedAt(started.toString())
                    .setEndedAt(Instant.now().toString()).setFinalContent(content)
                    .setStopReason(stopReason(state)).setMessages(transcripts.read(runId))
                    .setUsageLedger(ledger)
                    .setEvents(observation.events()).setToolEvents(observation.toolEvents())
                    .setRunEvents(runEvents(runId))
                    .setIterations(number(state.channels().get("iterations")))
                    .setError(state.failureMessage());
            result.setToolsUsed(observation.toolsUsed());
            return result;
        } catch (RuntimeException failure) {
            return new AgentRunResult().setRunId(runId).setStartedAt(started.toString())
                    .setEndedAt(Instant.now().toString()).setFinalContent(spec.getErrorMessage())
                    .setStopReason("failed").setError(message(failure));
        } finally {
            phases.detach(runId);
        }
    }

    private Map<String, Object> metadata(AgentRunSpec spec, String runId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (spec.getMetadata() != null) spec.getMetadata().forEach((key, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                metadata.put(key, value);
            }
        });
        metadata.put("model", clean(spec.getModel()));
        metadata.put("sessionId", clean(spec.getSessionKey()));
        metadata.put("transcriptReference", transcripts.reference(runId));
        metadata.put("workspace", spec.getWorkspace() != null ? spec.getWorkspace().toString() : "");
        metadata.put("maxToolResultChars", spec.getMaxToolResultChars());
        metadata.put("providerRetryMode", clean(spec.getProviderRetryMode()));
        metadata.put("contextWindowTokens", spec.getContextWindowTokens() != null
                ? Math.max(1, spec.getContextWindowTokens()) : 64_000);
        if (spec.getContextBlockLimit() != null) metadata.put("contextBlockLimit",
                Math.max(1, spec.getContextBlockLimit()));
        BudgetPolicy budget = effectiveBudget(spec.getBudgetPolicy(), defaultBudgetPolicy);
        metadata.put("budgetPolicy", budgetMap(budget));
        BudgetPolicy rootBudget = spec.getRootBudgetPolicy() != null
                ? spec.getRootBudgetPolicy() : budget;
        metadata.put("rootBudgetPolicy", budgetMap(rootBudget));
        metadata.put("modelOutputReserveTokens", Math.max(1L,
                Math.min(4_096L, Math.max(1L, budget.finalizationTokens()))));
        metadata.put("contextOffload", Map.of(
                "enabled", spec.isContextOffloadEnabled() && defaultOffload.isEnabled(),
                "previewChars", effectiveDefault(spec.getOffloadPreviewChars(), 1_200,
                        defaultOffload.getPreviewChars()),
                "readChunkChars", effectiveDefault(spec.getArtifactReadChunkChars(), 16_000,
                        defaultOffload.getReadChunkChars()),
                "maxArtifactBytesPerTool", effectiveDefault(spec.getMaxArtifactBytesPerTool(), 67_108_864L,
                        defaultOffload.getMaxArtifactBytesPerTool())));
        metadata.put("contextTriggerRatio", boundedRatio(spec.getContextTriggerRatio(), 0.80d));
        metadata.put("contextWarningRatio", boundedRatio(spec.getContextWarningRatio(), 0.60d));
        metadata.put("contextTargetRatio", boundedRatio(spec.getContextTargetRatio(), 0.60d));
        metadata.put("timezone", effectiveTimezone(spec.getTimezone()));
        metadata.put("maxParallelReadCalls", Math.max(1, spec.getMaxParallelReadCalls()));
        metadata.put("requireReadReceipt", spec.isRequireReadReceipt());
        metadata.put("externalActionsEnabled", spec.isExternalActionsEnabled());
        ModelCard.Pricing pricing = spec.getModelPricing() != null ? spec.getModelPricing() : defaultPricing;
        if (pricing != null) metadata.put("modelPricing", pricingMap(pricing));
        return Map.copyOf(metadata);
    }

    private String effectiveTimezone(String configured) {
        String value = clean(configured);
        return value.isBlank() || ("UTC".equals(value) && !"UTC".equals(defaultTimezone))
                ? defaultTimezone : value;
    }

    private static BudgetPolicy effectiveBudget(BudgetPolicy configured, BudgetPolicy fallback) {
        if (configured == null) return fallback;
        return !configured.limited() && fallback != null && fallback.limited() ? fallback : configured;
    }

    private static Map<String, Object> budgetMap(BudgetPolicy policy) {
        BudgetPolicy value = policy != null ? policy : BudgetPolicy.unlimited();
        Map<String, Object> result = new LinkedHashMap<>();
        if (value.maxTotalTokens() != null) result.put("maxTotalTokens", value.maxTotalTokens());
        if (value.maxCostMicrousd() != null) result.put("maxCostMicrousd", value.maxCostMicrousd());
        if (value.maxActiveSeconds() != null) result.put("maxActiveSeconds", value.maxActiveSeconds());
        if (value.maxToolCalls() != null) result.put("maxToolCalls", value.maxToolCalls());
        result.put("finalizationTokens", value.finalizationTokens());
        if (!value.parentRunId().isBlank()) result.put("parentRunId", value.parentRunId());
        return Map.copyOf(result);
    }

    private static Map<String, Object> pricingMap(ModelCard.Pricing pricing) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currency", pricing.currency());
        result.put("unitTokens", pricing.unitTokens());
        if (pricing.inputUsd() != null) result.put("inputUsd", pricing.inputUsd().toPlainString());
        if (pricing.outputUsd() != null) result.put("outputUsd", pricing.outputUsd().toPlainString());
        if (pricing.cachedInputUsd() != null) result.put("cachedInputUsd", pricing.cachedInputUsd().toPlainString());
        return Map.copyOf(result);
    }

    private static int effectiveDefault(int value, int conventionalDefault, int fallback) {
        int selected = value == conventionalDefault && fallback > 0 ? fallback : value;
        return Math.max(1, selected);
    }
    private static long effectiveDefault(long value, long conventionalDefault, long fallback) {
        long selected = value == conventionalDefault && fallback > 0 ? fallback : value;
        return Math.max(1L, selected);
    }
    private static double boundedRatio(double value, double fallback) {
        double selected = value > 0d && value <= 1d ? value : fallback;
        return Math.max(0.05d, Math.min(1d, selected));
    }

    private static String goal(AgentRunSpec spec) {
        List<Map<String, Object>> messages = spec.getInitialMessages();
        if (messages != null) for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> message = messages.get(index);
            if ("user".equals(String.valueOf(message.get("role")))) {
                String content = String.valueOf(message.getOrDefault("content", "")).trim();
                if (!content.isBlank()) return content;
            }
        }
        return "agent invocation";
    }

    private static String resultContent(RunState state) {
        Object result = state.channels().get("result");
        if (result instanceof Map<?, ?> map) return String.valueOf(map.containsKey("content") ? map.get("content") : "");
        if (state.status() == RunStatus.WAITING) return "Run is waiting: " + state.waitReason();
        return "";
    }
    private static String stopReason(RunState state) {
        if (state.status() == RunStatus.COMPLETED) {
            String value = clean(String.valueOf(state.channels().getOrDefault("stopReason", "stop")));
            return value.isBlank() ? "stop" : value;
        }
        if (state.status() == RunStatus.CANCELLED) return "cancelled";
        if (state.status() == RunStatus.FAILED) return "error";
        if (state.waitReason() instanceof WaitReason.ApprovalWait) return "approval_required";
        return state.status().name().toLowerCase(java.util.Locale.ROOT);
    }
    private List<Map<String, Object>> runEvents(String runId) {
        if (!(runtime instanceof RunQuery query)) return List.of();
        return query.events(runId).stream().map(event -> Map.<String, Object>of(
                "sequence", event.sequence(), "eventId", event.eventId(), "type", event.type(),
                "commitSequence", event.commitSequence(), "occurredAt", event.occurredAt().toString(),
                "payload", event.payload())).toList();
    }
    private static String parent(AgentRunSpec spec) { return stringMetadata(spec, "parentRunId"); }
    private static String retryOf(AgentRunSpec spec) { return stringMetadata(spec, "retryOfRunId"); }
    private static String root(AgentRunSpec spec, String runId) {
        String root = stringMetadata(spec, "rootRunId"); return root.isBlank() ? runId : root;
    }
    private static List<String> dependencies(AgentRunSpec spec) {
        Object value = spec.getMetadata() != null ? spec.getMetadata().get("dependencies") : null;
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
    private static String stringMetadata(AgentRunSpec spec, String key) {
        return spec.getMetadata() != null ? clean(String.valueOf(spec.getMetadata().getOrDefault(key, ""))) : "";
    }
    private static int number(Object value) { return value instanceof Number number ? Math.max(0, number.intValue()) : 0; }
    private static List<Map<String, Object>> copyMessages(List<Map<String, Object>> messages) {
        return messages != null ? messages.stream().map(Map::copyOf).toList() : List.of();
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String message(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }
}
