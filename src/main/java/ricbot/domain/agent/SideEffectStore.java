package ricbot.domain.agent;

import java.util.Optional;
import java.util.Set;
import java.util.List;

public interface SideEffectStore {
    Optional<SideEffectRecord> load(String idempotencyKey);
    SideEffectClaim claim(SideEffectRecord reservation);
    default List<SideEffectRecord> list() { return List.of(); }

    /** Compare-and-set transition. The supplied record must be exactly one version newer. */
    default SideEffectRecord transition(SideEffectRecord record, long expectedVersion,
                                        Set<SideEffectStatus> allowedSources) {
        SideEffectRecord current = load(record.idempotencyKey()).orElseThrow(() ->
                new IllegalArgumentException("side effect does not exist"));
        if (current.version() != expectedVersion) throw new IllegalStateException("side effect version conflict");
        if (allowedSources != null && !allowedSources.contains(current.status())) {
            throw new IllegalStateException("side effect transition is not allowed from " + current.status());
        }
        throw new UnsupportedOperationException("side-effect store must implement atomic CAS transition");
    }

    static SideEffectStore disabled() {
        return Disabled.INSTANCE;
    }

    enum Disabled implements SideEffectStore {
        INSTANCE;
        public Optional<SideEffectRecord> load(String key) { return Optional.empty(); }
        public SideEffectClaim claim(SideEffectRecord value) { return new SideEffectClaim(value, true); }
        public SideEffectRecord transition(SideEffectRecord value, long expectedVersion,
                                           Set<SideEffectStatus> allowedSources) { return value; }
    }
}
