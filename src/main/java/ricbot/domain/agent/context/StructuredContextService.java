package ricbot.domain.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Applies the runtime's structured, non-destructive context compaction policy. */
public final class StructuredContextService {
    private static final Logger log = LoggerFactory.getLogger(StructuredContextService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final int SAFETY_BUFFER = 4_096;

    private final LLMProvider provider;
    private final String model;
    private final SessionManager sessions;
    private final Integer contextWindowTokens;
    private final int maxCompletionTokens;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public StructuredContextService(LLMProvider provider, String model, SessionManager sessions,
                                    Integer contextWindowTokens, int maxCompletionTokens) {
        this.provider = provider;
        this.model = model;
        this.sessions = sessions;
        this.contextWindowTokens = contextWindowTokens;
        this.maxCompletionTokens = maxCompletionTokens;
    }

    public void maybeCompact(Session session) {
        if (session.getMessages().isEmpty() || contextWindowTokens == null || contextWindowTokens <= 0) return;
        synchronized (locks.computeIfAbsent(session.getKey(), ignored -> new Object())) {
            int budget = contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
            if (budget <= 0) return;
            ContextCompactionResult result = new ContextCompactor().compact(
                    session.getMessages(), budget, model, this::summarize);
            if (!result.compacted()) return;
            session.setMessages(new ArrayList<>(result.eventMessages()));
            session.setLastConsolidated(session.getMessages().size());
            sessions.save(session);
            log.info("session {} compacted {} -> {} tokens{}", session.getKey(), result.sourceTokens(),
                    result.resultTokens(), result.degraded() ? " using conservative fallback" : "");
        }
    }

    private StructuredContextSummary summarize(List<Map<String, Object>> source, String prompt) throws Exception {
        LLMResponse response = provider.chat(List.of(Map.of("role", "system", "content", prompt)),
                List.of(), model, null, null, null, null);
        String content = response.getContent() != null ? response.getContent().trim() : "";
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        if (content.isBlank()) throw new IllegalStateException("compact model returned an empty summary");
        return MAPPER.readValue(content, StructuredContextSummary.class);
    }
}
