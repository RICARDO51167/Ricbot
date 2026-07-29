package ricbot.domain.agent.interfacep;

import ricbot.domain.agent.dto.SideEffectClaim;
import ricbot.domain.agent.dto.SideEffectRecord;
import ricbot.domain.agent.eump.SideEffectStatus;

import java.util.Optional;
import java.util.Set;
import java.util.List;

public interface SideEffectStore {
    Optional<SideEffectRecord> load(String idempotencyKey);
    SideEffectClaim claim(SideEffectRecord reservation);
    List<SideEffectRecord> list();

    /** Compare-and-set transition. The supplied record must be exactly one version newer. */
    SideEffectRecord transition(SideEffectRecord record, long expectedVersion,
                                Set<SideEffectStatus> allowedSources);
}
