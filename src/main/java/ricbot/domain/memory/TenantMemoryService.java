package ricbot.domain.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.persistence.SharedStateStore;
import ricbot.infra.persistence.SharedValue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Multi-tenant layered memory backed by the shared CAS storage boundary. */
public final class TenantMemoryService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final SharedStateStore store;
    private final MemoryRetriever retriever;

    public TenantMemoryService(SharedStateStore store) {
        this(store, new MemoryRetriever());
    }

    public TenantMemoryService(SharedStateStore store, MemoryRetriever retriever) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.retriever = java.util.Objects.requireNonNull(retriever, "retriever");
    }

    public MemoryEntry upsert(String tenantId, MemoryEntry entry) {
        if (entry == null) throw new IllegalArgumentException("entry is required");
        String namespace = namespace(tenantId);
        try {
            byte[] content = MAPPER.writeValueAsBytes(entry.toMap());
            SharedValue current = store.get(namespace, entry.getId()).orElse(null);
            store.put(namespace, entry.getId(), content,
                    current != null ? current.version() : SharedStateStore.MUST_NOT_EXIST);
            return entry;
        } catch (Exception e) {
            throw new IllegalStateException("failed to upsert tenant memory", e);
        }
    }

    public List<MemoryEntry> entries(String tenantId) {
        return store.list(namespace(tenantId)).stream().map(this::decode).toList();
    }

    public List<MemoryRetriever.ScoredMemory> recall(String tenantId, String query, String taskGoal, int limit) {
        return retriever.score(entries(tenantId), query, taskGoal).stream()
                .limit(Math.max(1, limit)).toList();
    }

    public PromotionResult promoteEligible(String tenantId) {
        int episodic = 0;
        int semantic = 0;
        for (MemoryEntry entry : entries(tenantId)) {
            MemoryType before = entry.getMemoryType();
            if (before == MemoryType.WORKING
                    && entry.getConfidence() >= 0.70d && entry.getImportance() >= 0.60d) {
                entry.setMemoryType(MemoryType.EPISODIC).setScope(MemoryEntry.SCOPE_LONG_TERM);
                upsert(tenantId, entry);
                episodic++;
            } else if (before == MemoryType.EPISODIC
                    && entry.getConfidence() >= 0.80d && entry.getAccessCount() >= 3) {
                entry.setMemoryType(MemoryType.SEMANTIC).setScope(MemoryEntry.SCOPE_LONG_TERM);
                upsert(tenantId, entry);
                semantic++;
            }
        }
        return new PromotionResult(episodic, semantic, snapshot(tenantId));
    }

    public LayerSnapshot snapshot(String tenantId) {
        Map<MemoryType, Integer> counts = new EnumMap<>(MemoryType.class);
        for (MemoryType type : MemoryType.values()) counts.put(type, 0);
        for (MemoryEntry entry : entries(tenantId)) counts.compute(entry.getMemoryType(), (key, value) -> value + 1);
        return new LayerSnapshot(Map.copyOf(counts), counts.values().stream().mapToInt(Integer::intValue).sum());
    }

    private MemoryEntry decode(SharedValue value) {
        try { return MemoryEntry.fromMap(MAPPER.readValue(value.content(), MAP)); }
        catch (Exception e) { throw new IllegalStateException("failed to decode tenant memory", e); }
    }
    private static String namespace(String tenantId) {
        String clean = tenantId != null ? tenantId.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("tenantId is required");
        return "memory:" + clean;
    }

    public record LayerSnapshot(Map<MemoryType, Integer> counts, int total) { }
    public record PromotionResult(int promotedToEpisodic, int promotedToSemantic, LayerSnapshot snapshot) { }
}
