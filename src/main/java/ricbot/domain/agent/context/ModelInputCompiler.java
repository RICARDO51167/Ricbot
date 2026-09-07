package ricbot.domain.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.runtime.dto.RuntimeDigest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles the complete, current request immediately before each MODEL invocation. */
public final class ModelInputCompiler {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ContextTokenAccountant accountant;

    public ModelInputCompiler(ContextTokenAccountant accountant) {
        this.accountant = accountant != null ? accountant : new ConservativeContextTokenAccountant();
    }

    public ModelInputPlan compile(List<Map<String, Object>> durableMessages,
                                  Object workingSummary,
                                  List<Map<String, Object>> tools,
                                  Set<String> basicToolNames,
                                  Map<String, Object> runtimeHints,
                                  long outputReserve,
                                  long contextWindow,
                                  String model,
                                  boolean finalizing) {
        List<ContextSegment> segments = ContextSegment.parse(durableMessages);
        List<Map<String, Object>> compiled = new ArrayList<>();
        List<ContextCandidate> candidates = new ArrayList<>();
        int leadingSystemSegments = 0;
        while (leadingSystemSegments < segments.size()
                && segments.get(leadingSystemSegments).kind() == ContextSegment.Kind.SYSTEM) {
            addSegment(compiled, candidates, segments.get(leadingSystemSegments));
            leadingSystemSegments++;
        }
        if (workingSummary != null) {
            String text = stringify(workingSummary);
            compiled.add(Map.of("role", "system", "name", "ricbot_working_summary", "content", text));
            candidates.add(new ContextCandidate("working-summary", "workingSummary", text, 90,
                    estimate(text), null, true, "active run summary"));
        }
        Map<String, Object> hints = new LinkedHashMap<>(runtimeHints != null ? runtimeHints : Map.of());
        if (finalizing) hints.put("finalization", Map.of("toolsAllowed", false,
                "instruction", "Give a concise final summary of progress, evidence, and unfinished work."));
        // Dynamic hints precede conversation segments so an assistant tool call and all of its
        // results remain adjacent and, when current, at the end of the provider request.
        if (!hints.isEmpty()) compiled.add(Map.of("role", "system", "name", "ricbot_runtime",
                "content", "Runtime hints (dynamic; not identity instructions): " + stringify(hints)));
        for (int index = leadingSystemSegments; index < segments.size(); index++) {
            addSegment(compiled, candidates, segments.get(index));
        }
        List<Map<String, Object>> visible = finalizing ? List.of() : List.copyOf(tools != null ? tools : List.of());
        TokenEstimate tokens = accountant.count(new ModelRequestShape(compiled, visible, outputReserve,
                contextWindow, model));
        boolean narrowed = false;
        if (!tokens.fits() && !visible.isEmpty() && basicToolNames != null) {
            List<Map<String, Object>> basic = visible.stream().filter(tool -> basicToolNames.contains(toolName(tool))).toList();
            if (basic.size() < visible.size()) {
                visible = basic;
                narrowed = true;
                tokens = accountant.count(new ModelRequestShape(compiled, visible, outputReserve, contextWindow, model));
            }
        }
        return new ModelInputPlan(compiled, visible, candidates, hints, tokens,
                RuntimeDigest.sha256(Map.of("messages", compiled, "tools", visible,
                        "outputReserve", outputReserve, "model", model)), narrowed);
    }

    private static String toolName(Map<String, Object> tool) {
        Object function = tool.get("function");
        if (function instanceof Map<?, ?> map && map.get("name") != null) return String.valueOf(map.get("name"));
        return String.valueOf(tool.getOrDefault("name", ""));
    }
    private static void addSegment(List<Map<String, Object>> compiled,
                                   List<ContextCandidate> candidates, ContextSegment segment) {
        compiled.addAll(segment.messages());
        String text = stringify(segment.messages());
        candidates.add(new ContextCandidate(segment.segmentId(), "segment:" + segment.kind(), text,
                segment.kind() == ContextSegment.Kind.USER_TURN ? 100 : 80,
                estimate(text), null, true, "complete interaction segment"));
    }
    private static long estimate(String text) {
        return Math.max(1, (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 2L) / 3L);
    }
    private static String stringify(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception ignored) { return String.valueOf(value); }
    }
}
