package ricbot.domain.agent;

import java.util.Optional;

public interface SideEffectStore {
    Optional<SideEffectRecord> load(String idempotencyKey);
    SideEffectClaim claim(SideEffectRecord reservation);
    SideEffectRecord save(SideEffectRecord record);

    static SideEffectStore disabled() {
        return Disabled.INSTANCE;
    }

    enum Disabled implements SideEffectStore {
        INSTANCE;
        public Optional<SideEffectRecord> load(String key) { return Optional.empty(); }
        public SideEffectClaim claim(SideEffectRecord value) { return new SideEffectClaim(value, true); }
        public SideEffectRecord save(SideEffectRecord value) { return value; }
    }
}
