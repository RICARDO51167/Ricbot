package ricbot.domain.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fail-closed registry for event schema upgrades. */
public final class RuntimeEventUpcasters {
    private final Map<Integer, RuntimeEventUpcaster> bySource;

    public RuntimeEventUpcasters(List<RuntimeEventUpcaster> upcasters) {
        Map<Integer, RuntimeEventUpcaster> indexed = new HashMap<>();
        for (RuntimeEventUpcaster upcaster : upcasters != null ? upcasters : List.<RuntimeEventUpcaster>of()) {
            if (upcaster.targetVersion() != upcaster.sourceVersion() + 1) {
                throw new IllegalArgumentException("upcasters must advance exactly one schema version");
            }
            if (indexed.put(upcaster.sourceVersion(), upcaster) != null) {
                throw new IllegalArgumentException("duplicate upcaster for schema " + upcaster.sourceVersion());
            }
        }
        bySource = Map.copyOf(indexed);
    }

    public JsonNode upcast(int schemaVersion, String eventType, String payloadType, JsonNode payload) {
        if (schemaVersion > RuntimeEventEnvelope.CURRENT_SCHEMA_VERSION) {
            throw new UnknownRuntimeEventVersionException(schemaVersion);
        }
        int version = schemaVersion;
        JsonNode current = payload;
        while (version < RuntimeEventEnvelope.CURRENT_SCHEMA_VERSION) {
            RuntimeEventUpcaster upcaster = bySource.get(version);
            if (upcaster == null) throw new UnknownRuntimeEventVersionException(version);
            current = upcaster.upcast(eventType, payloadType, current);
            version = upcaster.targetVersion();
        }
        return current;
    }
}
