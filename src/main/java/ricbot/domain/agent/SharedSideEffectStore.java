package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.persistence.SharedStateStore;
import ricbot.infra.persistence.SharedValue;

import java.util.Optional;

/** Cluster-safe idempotency ledger backed by compare-and-set storage. */
public final class SharedSideEffectStore implements SideEffectStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String NAMESPACE = "agent-side-effects";

    private final SharedStateStore store;

    public SharedSideEffectStore(SharedStateStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    @Override
    public Optional<SideEffectRecord> load(String idempotencyKey) {
        String key = required(idempotencyKey);
        return store.get(NAMESPACE, key).map(value -> read(value, key));
    }

    @Override
    public SideEffectClaim claim(SideEffectRecord reservation) {
        java.util.Objects.requireNonNull(reservation, "reservation");
        try {
            store.put(NAMESPACE, reservation.idempotencyKey(), write(reservation),
                    SharedStateStore.MUST_NOT_EXIST);
            return new SideEffectClaim(reservation, true);
        } catch (IllegalStateException conflict) {
            Optional<SideEffectRecord> existing = load(reservation.idempotencyKey());
            if (existing.isPresent()) return new SideEffectClaim(existing.orElseThrow(), false);
            throw conflict;
        }
    }

    @Override
    public SideEffectRecord save(SideEffectRecord record) {
        java.util.Objects.requireNonNull(record, "record");
        String key = required(record.idempotencyKey());
        SharedValue current = store.get(NAMESPACE, key).orElseThrow(() ->
                new IllegalStateException("side-effect reservation does not exist"));
        store.put(NAMESPACE, key, write(record), current.version());
        return record;
    }

    private static SideEffectRecord read(SharedValue value, String key) {
        try {
            SideEffectRecord record = MAPPER.readValue(value.content(), SideEffectRecord.class);
            if (!key.equals(record.idempotencyKey())) {
                throw new IllegalStateException("side-effect identity mismatch");
            }
            return record;
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("failed to deserialize side-effect record", e);
        }
    }

    private static byte[] write(SideEffectRecord record) {
        try {
            return MAPPER.writeValueAsBytes(record);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize side-effect record", e);
        }
    }

    private static String required(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        return clean;
    }
}
