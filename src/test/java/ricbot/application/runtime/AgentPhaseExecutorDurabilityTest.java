package ricbot.application.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentRunResult;
import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.domain.config.ModelCard;
import ricbot.domain.runtime.*;
import ricbot.domain.security.ApprovalService;
import ricbot.infra.runtime.SqliteDurableRuntimeStore;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.tool.api.BuiltinTool;
import ricbot.tool.api.ToolEffectPolicy;
import ricbot.tool.api.ToolRegistry;

import java.net.http.HttpTimeoutException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentPhaseExecutorDurabilityTest {
    @Test void contextPhaseCompactsDurablyAndModelLedgerReservesTheExactCompiledRequest(
            @TempDir Path workspace) {
        AtomicReference<List<Map<String, Object>>> sentMessages = new AtomicReference<>(List.of());
        LLMProvider provider = provider((messages, tools) -> {
            sentMessages.set(messages);
            return new LLMResponse("done").setFinishReason("stop")
                    .setUsage(Map.of("prompt_tokens", 300, "completion_tokens", 10, "total_tokens", 310));
        });
        List<Map<String, Object>> transcript = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> Map.<String, Object>of("role", "user",
                        "content", ("message-" + index + " ").repeat(45)))
                .toList();
        try (SqliteRuntimeStore application = new SqliteRuntimeStore(workspace);
             SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(
                     workspace.resolve(".ricbot/runtime-v6.db"))) {
            application.initialize("context-run", transcript);
            AgentPhaseExecutor phases = phases(store, application, provider, workspace, Clock.systemUTC());
            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, phases, application,
                    Clock.systemUTC(), "context-test", Duration.ofSeconds(30))) {
                RunView completed = runtime.start(spec("context-run", "compact", Map.of(
                        "model", "test-model", "contextWindowTokens", 1_200,
                        "modelOutputReserveTokens", 100, "contextTriggerRatio", 0.50d)));

                assertEquals(RunStatus.COMPLETED, completed.state().status(),
                        completed.state().failureCode() + ": " + completed.state().failureMessage());
                assertTrue(((Number) completed.state().channels().get("compactionCount")).intValue() > 0);
                assertTrue(sentMessages.get().size() < transcript.size());
                Map<?, ?> plan = (Map<?, ?>) completed.state().channels().get("modelInputPlan");
                assertTrue(((Number) plan.get("reservedTokens")).longValue() > 0);
                String invocationId = invocationId(runtime, "context-run", "MODEL_INVOCATION_OBSERVED");
                assertEquals(((Number) plan.get("reservedTokens")).longValue(),
                        store.modelInvocation(invocationId).orElseThrow().reservedTokens());
            }
        }
    }

    @Test void compositionDefaultsBecomeDurablePolicyMetadata(@TempDir Path workspace) {
        LLMProvider provider = provider((messages, tools) -> new LLMResponse("done").setFinishReason("stop"));
        BudgetPolicy budget = new BudgetPolicy(20_000L, 50_000L, 60L, 4L, 512L, "");
        Config.ContextOffloadConfig offload = new Config.ContextOffloadConfig();
        offload.setEnabled(false);
        offload.setPreviewChars(333);
        offload.setReadChunkChars(4_444);
        offload.setMaxArtifactBytesPerTool(55_555L);
        ModelCard.Pricing pricing = new ModelCard.Pricing("USD", 1_000_000,
                new BigDecimal("1.25"), new BigDecimal("5.00"), null);
        try (SqliteRuntimeStore application = new SqliteRuntimeStore(workspace);
             SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(
                     workspace.resolve(".ricbot/runtime-v6.db"))) {
            AgentPhaseExecutor phases = phases(store, application, provider, workspace, Clock.systemUTC());
            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, phases, application,
                    Clock.systemUTC(), "metadata-test", Duration.ofSeconds(30))) {
                AgentRuntimeExecutionService service = new AgentRuntimeExecutionService(runtime, application, phases,
                        budget, offload, "Asia/Shanghai", pricing);
                AgentRunSpec invocation = new AgentRunSpec().setRunId("metadata-run")
                        .setInitialMessages(List.of(Map.of("role", "user", "content", "hello")))
                        .setModel("test-model");

                AgentRunResult result = service.run(invocation);
                RunState state = runtime.get("metadata-run").orElseThrow().state();
                Map<?, ?> persistedBudget = (Map<?, ?>) state.spec().metadata().get("budgetPolicy");
                Map<?, ?> persistedOffload = (Map<?, ?>) state.spec().metadata().get("contextOffload");
                Map<?, ?> persistedPricing = (Map<?, ?>) state.spec().metadata().get("modelPricing");

                assertEquals("stop", result.getStopReason());
                assertEquals(20_000L, ((Number) persistedBudget.get("maxTotalTokens")).longValue());
                assertEquals(false, persistedOffload.get("enabled"));
                assertEquals(333, ((Number) persistedOffload.get("previewChars")).intValue());
                assertEquals("Asia/Shanghai", state.spec().metadata().get("timezone"));
                assertEquals("1.25", persistedPricing.get("inputUsd"));
            }
        }
    }

    @Test void ambiguousModelDispatchUsesRetryWaitAndMarksTheNextAttemptAsPossiblyDuplicated(
            @TempDir Path workspace) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider((messages, tools) -> {
            if (calls.getAndIncrement() == 0) throw new HttpTimeoutException("response timed out");
            return new LLMResponse("recovered").setFinishReason("stop");
        });
        try (SqliteRuntimeStore application = new SqliteRuntimeStore(workspace);
             SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(
                     workspace.resolve(".ricbot/runtime-v6.db"))) {
            AgentPhaseExecutor phases = phases(store, application, provider, workspace, clock);
            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, phases, application,
                    clock, "model-retry-test", Duration.ofSeconds(30))) {
                RunView waiting = runtime.start(spec("retry-model", "root", Map.of(
                        "model", "test-model", "providerRetryMode", "standard", "maxModelAttempts", 3)));

                assertEquals(RunStatus.WAITING, waiting.state().status());
                assertTrue(waiting.state().waitReason() instanceof WaitReason.RetryWait);
                assertEquals(1, calls.get(), "retry must not happen inside the provider boundary");
                String unknownId = invocationId(runtime, "retry-model", "MODEL_INVOCATION_UNKNOWN");
                assertEquals(ModelInvocation.Status.UNKNOWN,
                        store.modelInvocation(unknownId).orElseThrow().status());

                clock.advance(Duration.ofSeconds(2));
                runtime.runScheduledWork();
                RunView completed = runtime.get("retry-model").orElseThrow();
                assertEquals(RunStatus.COMPLETED, completed.state().status());
                assertEquals(2, calls.get());
                String observedId = invocationId(runtime, "retry-model", "MODEL_INVOCATION_OBSERVED");
                assertTrue(store.modelInvocation(observedId).orElseThrow().possibleDuplicateCharge());

                int beforeReplay = calls.get();
                assertEquals(completed.state(), runtime.replayState("retry-model", Long.MAX_VALUE).state());
                assertEquals(beforeReplay, calls.get(), "state replay must not call the provider");
            }
        }
    }

    @Test void observedModelResponseSurvivesCrashBeforeModelSuperstepCommit(@TempDir Path workspace) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider((messages, tools) -> {
            calls.incrementAndGet();
            return new LLMResponse("durable response").setFinishReason("stop");
        });
        CrashInjector crashDuringModelCommit = (point, detail) -> {
            if (point == CrashInjector.Point.PENDING_WRITE_STAGED
                    && RuntimePhase.MODEL.name().equals(detail.get("phase"))) {
                throw new CrashInjector.InjectedCrash(point);
            }
        };
        try (SqliteRuntimeStore application = new SqliteRuntimeStore(workspace);
             SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(
                     workspace.resolve(".ricbot/runtime-v6.db"))) {
            application.initialize("model-precommit", List.of(
                    Map.of("role", "user", "content", "hello")));
            AgentPhaseExecutor crashingPhases = new AgentPhaseExecutor(store, application, provider,
                    new ToolRegistry(), new ApprovalService(application.approvalStore()), "test-model",
                    workspace, clock, "model-precommit-a", crashDuringModelCommit);
            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, crashingPhases,
                    application, clock, "model-precommit-a", Duration.ofSeconds(30), crashDuringModelCommit)) {
                assertThrows(CrashInjector.InjectedCrash.class,
                        () -> runtime.start(spec("model-precommit", "hello", Map.of("model", "test-model"))));
                RunState interrupted = runtime.get("model-precommit").orElseThrow().state();
                assertEquals(1L, ((Number) interrupted.channels().get("transcriptCursor")).longValue());
                assertEquals(2L, application.size("model-precommit"),
                        "the deterministic Assistant entry may exist before its superstep commits");
                assertEquals(1, application.read("model-precommit", 1L).size(),
                        "uncommitted Transcript entries must be invisible to model input");
            }

            clock.advance(Duration.ofSeconds(31));
            AgentPhaseExecutor recoveredPhases = phases(store, application, provider, workspace, clock);
            try (LocalDurableAgentRuntime recovered = new LocalDurableAgentRuntime(store, recoveredPhases,
                    application, clock, "model-precommit-b", Duration.ofSeconds(30))) {
                recovered.runScheduledWork();
                RunState completed = recovered.get("model-precommit").orElseThrow().state();
                assertEquals(RunStatus.COMPLETED, completed.status(),
                        completed.failureCode() + ": " + completed.failureMessage());
                assertEquals(1, calls.get(), "recovery must reuse the OBSERVED response");
                assertEquals(2L, ((Number) completed.channels().get("transcriptCursor")).longValue());
                assertEquals(2L, application.size("model-precommit"),
                        "recovery must reuse the deterministic Assistant entry instead of appending a duplicate");
                assertEquals(completed, recovered.replayState("model-precommit", Long.MAX_VALUE).state());
            }
        }
    }

    @Test void manualUnknownModelPolicyFailsClosedInsteadOfWaitingForAnImpossibleEvent(@TempDir Path workspace) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LLMProvider provider = provider((messages, tools) -> {
            throw new HttpTimeoutException("response timed out");
        });
        try (SqliteRuntimeStore application = new SqliteRuntimeStore(workspace);
             SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(
                     workspace.resolve(".ricbot/runtime-v6.db"))) {
            AgentPhaseExecutor phases = phases(store, application, provider, workspace, clock);
            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, phases, application,
                    clock, "model-manual-test", Duration.ofSeconds(30))) {
                RunView failed = runtime.start(spec("manual-model", "root", Map.of(
                        "model", "test-model", "providerRetryMode", "never")));
                assertEquals(RunStatus.FAILED, failed.state().status());
                assertEquals("MODEL_OUTCOME_UNKNOWN", failed.state().failureCode());
                assertTrue(runtime.events("manual-model").stream()
                        .anyMatch(event -> event.type().equals("MODEL_INVOCATION_UNKNOWN")));
            }
        }
    }

    @Test void modelRuntimeControlCreatesPolicyBoundedChildDagThroughDelegatePhase(@TempDir Path workspace) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AtomicReference<List<Map<String, Object>>> offeredTools = new AtomicReference<>(List.of());
        LLMProvider provider = provider((messages, tools) -> {
            offeredTools.set(tools);
            boolean parent = messages.stream().anyMatch(message ->
                    String.valueOf(message.getOrDefault("content", "")).contains("coordinate children"));
            boolean alreadySpawned = messages.stream().anyMatch(message ->
                    "spawn_child_runs".equals(String.valueOf(message.getOrDefault("name", ""))));
            if (parent && !alreadySpawned) {
                return new LLMResponse().setContent("").setFinishReason("tool_calls").setToolCalls(List.of(
                        new ToolCallRequest("delegate-1", "spawn_child_runs", Map.of(
                                "children", List.of(
                                        Map.of("key", "research", "goal", "research the input"),
                                        Map.of("key", "synthesize", "goal", "synthesize the result",
                                                "dependsOn", List.of("research"))),
                                "waitForAll", true))));
            }
            return new LLMResponse(parent ? "parent complete" : "child complete").setFinishReason("stop");
        });
        try (SqliteRuntimeStore application = new SqliteRuntimeStore(workspace);
             SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(
                     workspace.resolve(".ricbot/runtime-v6.db"))) {
            AgentPhaseExecutor phases = phases(store, application, provider, workspace, clock);
            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, phases, application,
                    clock, "child-control-test", Duration.ofSeconds(30))) {
                RunView waiting = runtime.start(spec("parent-control", "coordinate children", Map.of(
                        "model", "test-model", "maxChildRuns", 2, "maxChildDepth", 2)));
                assertEquals(RunStatus.WAITING, waiting.state().status());
                assertTrue(waiting.state().waitReason() instanceof WaitReason.ChildRunWait);
                List<RunView> children = runtime.children("parent-control");
                assertEquals(2, children.size());
                RunState research = children.stream().map(RunView::state)
                        .filter(child -> child.spec().goal().startsWith("research")).findFirst().orElseThrow();
                RunState synthesis = children.stream().map(RunView::state)
                        .filter(child -> child.spec().goal().startsWith("synthesize")).findFirst().orElseThrow();
                assertEquals(List.of(research.spec().runId()), synthesis.spec().dependencies());
                assertTrue(offeredTools.get().stream().anyMatch(definition ->
                        "spawn_child_runs".equals(String.valueOf(
                                ((Map<?, ?>) definition.get("function")).get("name")))));

                for (int i = 0; i < 5 && !runtime.get("parent-control").orElseThrow()
                        .state().status().terminal(); i++) runtime.runScheduledWork();

                assertEquals(RunStatus.COMPLETED,
                        runtime.get("parent-control").orElseThrow().state().status());
                assertTrue(runtime.children("parent-control").stream()
                        .allMatch(child -> child.state().status() == RunStatus.COMPLETED));
            }
        }
    }

    @Test void busyEffectResourceUsesDurableRetryWaitInsteadOfHoldingAnActivation(@TempDir Path workspace) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new BuiltinTool() {
            @Override public String getName() { return "write_once"; }
            @Override public String getDescription() { return "test durable write"; }
            @Override public ToolEffectPolicy effectPolicy() {
                return ToolEffectPolicy.idempotent(Duration.ofSeconds(10), ToolEffectPolicy.Approval.NEVER);
            }
            @Override public Object execute(Map<String, Object> params) {
                executions.incrementAndGet();
                return "written";
            }
        });
        LLMProvider provider = provider((messages, definitions) -> {
            boolean completedTool = messages.stream().anyMatch(message ->
                    "write_once".equals(String.valueOf(message.getOrDefault("name", ""))));
            if (completedTool) return new LLMResponse("complete").setFinishReason("stop");
            return new LLMResponse().setContent("").setFinishReason("tool_calls").setToolCalls(List.of(
                    new ToolCallRequest("write-call", "write_once", Map.of())));
        });
        try (SqliteRuntimeStore application = new SqliteRuntimeStore(workspace);
             SqliteDurableRuntimeStore store = new SqliteDurableRuntimeStore(
                     workspace.resolve(".ricbot/runtime-v6.db"))) {
            assertTrue(store.acquireResources("blocker", "other-owner",
                    List.of("run:effect-retry:write_once"), clock.instant(), Duration.ofSeconds(10)));
            AgentPhaseExecutor phases = new AgentPhaseExecutor(store, application, provider, tools,
                    new ApprovalService(application.approvalStore()), "test-model", workspace, clock, "phase-test");
            try (LocalDurableAgentRuntime runtime = new LocalDurableAgentRuntime(store, phases, application,
                    clock, "effect-retry-test", Duration.ofSeconds(30))) {
                RunView waiting = runtime.start(spec("effect-retry", "write once", Map.of("model", "test-model")));
                assertEquals(RunStatus.WAITING, waiting.state().status());
                assertTrue(waiting.state().waitReason() instanceof WaitReason.RetryWait);
                assertTrue(waiting.state().waitReason().correlationId().startsWith("effect-retry:"));
                assertEquals(0, executions.get());
                assertTrue(store.effect("effect:effect-retry:write-call").orElseThrow()
                        .committedSuperstep() >= 0, "a PREPARED effect consumed by RetryWait must be committed");

                clock.advance(Duration.ofSeconds(11));
                runtime.runScheduledWork();
                assertEquals(RunStatus.COMPLETED, runtime.get("effect-retry").orElseThrow().state().status());
                assertEquals(1, executions.get());
            }
        }
    }

    private static AgentPhaseExecutor phases(SqliteDurableRuntimeStore store, SqliteRuntimeStore application,
                                              LLMProvider provider, Path workspace, Clock clock) {
        return new AgentPhaseExecutor(store, application, provider, new ToolRegistry(),
                new ApprovalService(application.approvalStore()), "test-model", workspace, clock, "phase-test");
    }

    private static RunSpec spec(String runId, String goal, Map<String, Object> metadata) {
        return new RunSpec(runId, "", runId, "", List.of(), goal, "default", 64, metadata);
    }

    private static String invocationId(LocalDurableAgentRuntime runtime, String runId, String eventType) {
        return runtime.events(runId).stream().filter(event -> event.type().equals(eventType))
                .reduce((first, second) -> second).map(event -> String.valueOf(event.payload().get("invocationId")))
                .orElseThrow();
    }

    private static LLMProvider provider(Chat chat) {
        return new LLMProvider() {
            { setDefaultModel("test-model"); }
            @Override public LLMResponse chat(List<Map<String, Object>> messages,
                                              List<Map<String, Object>> tools,
                                              String model, Integer maxTokens, Double temperature,
                                              String reasoningEffort, Object toolChoice) throws Exception {
                return chat.invoke(messages, tools);
            }
        };
    }

    @FunctionalInterface
    private interface Chat {
        LLMResponse invoke(List<Map<String, Object>> messages, List<Map<String, Object>> tools) throws Exception;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private MutableClock(Instant instant) { this.instant = new AtomicReference<>(instant); }
        void advance(Duration duration) { instant.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
