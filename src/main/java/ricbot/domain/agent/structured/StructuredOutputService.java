package ricbot.domain.agent.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.integration.llm.api.LLMFailureException;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.domain.agent.usage.UsageDelta;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.agent.usage.UsagePricer;
import ricbot.domain.config.ModelCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single owner for provider mode selection, parsing, validation and one bounded repair call. */
public final class StructuredOutputService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final LLMProvider provider;
    private final ModelCard.Pricing pricing;
    public StructuredOutputService(LLMProvider provider) { this(provider, null); }
    public StructuredOutputService(LLMProvider provider, ModelCard.Pricing pricing) {
        this.provider = java.util.Objects.requireNonNull(provider); this.pricing = pricing;
    }

    public <T> StructuredResult<T> execute(List<Map<String, Object>> messages, String model,
                                           StructuredRequest<T> request, boolean strictTools,
                                           boolean jsonMode) throws Exception {
        StructuredResult.Mode mode = strictTools ? StructuredResult.Mode.STRICT_TOOL
                : jsonMode ? StructuredResult.Mode.JSON : StructuredResult.Mode.TEXT_FALLBACK;
        String invalid = "";
        UsageLedger usage = UsageLedger.empty();
        List<Map<String, Object>> current = new ArrayList<>(messages != null ? messages : List.of());
        for (int attempt = 0; attempt <= request.maxRepairCalls(); attempt++) {
            if (attempt > 0) current.add(Map.of("role", "system", "content",
                    "The prior structured response was invalid: " + invalid
                            + ". Return exactly one value conforming to the declared JSON schema."));
            long started = System.nanoTime();
            LLMResponse response = call(current, model, request, mode);
            long activeMillis = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
            UsageDelta observed = UsageDelta.model(model, response.getUsage(), activeMillis);
            if (attempt > 0) observed = new UsageDelta(observed.inputTokens(), observed.outputTokens(),
                    observed.totalTokens(), 0, 0, 1, 0, observed.activeMillis(), 0,
                    observed.model(), false);
            usage = usage.plus(UsagePricer.price(observed, pricing));
            try {
                T value = decode(response, request, mode);
                if (!request.validator().test(value)) throw new IllegalArgumentException("business validation failed");
                return new StructuredResult<>(value, mode, attempt, "", usage);
            } catch (Exception failure) {
                invalid = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
                if (attempt == request.maxRepairCalls()) return new StructuredResult<>(null, mode, attempt, invalid, usage);
                current.add(Map.of("role", "assistant", "content", response.getContent() != null ? response.getContent() : ""));
            }
        }
        return new StructuredResult<>(null, mode, request.maxRepairCalls(), invalid, usage);
    }

    private <T> LLMResponse call(List<Map<String, Object>> messages, String model, StructuredRequest<T> request,
                                 StructuredResult.Mode mode) throws Exception {
        if (mode == StructuredResult.Mode.STRICT_TOOL) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", request.name()); function.put("description", request.description());
            function.put("strict", true); function.put("parameters", request.schema());
            Map<String, Object> tool = Map.of("type", "function", "function", function);
            return LLMFailureException.requireSuccess(provider.chat(messages, List.of(tool), model, null, 0d, null,
                    Map.of("type", "function", "function", Map.of("name", request.name()))));
        }
        List<Map<String, Object>> prompted = new ArrayList<>(messages);
        prompted.add(Map.of("role", "system", "content", "Return JSON only. Schema: " + json(request.schema())));
        return LLMFailureException.requireSuccess(provider.chat(prompted, List.of(), model, null, 0d, null, null));
    }

    private <T> T decode(LLMResponse response, StructuredRequest<T> request, StructuredResult.Mode mode) throws Exception {
        Object value;
        if (mode == StructuredResult.Mode.STRICT_TOOL) {
            if (!response.hasToolCalls()) throw new IllegalArgumentException("provider did not return the forced tool");
            value = response.getToolCalls().get(0).getArguments();
        } else {
            String content = response.getContent() != null ? response.getContent().trim() : "";
            if (content.startsWith("```")) content = content.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
            if (content.isBlank()) throw new IllegalArgumentException("empty structured response");
            value = MAPPER.readTree(content);
        }
        return MAPPER.convertValue(value, request.responseType());
    }
    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception failure) { return String.valueOf(value); }
    }
}
