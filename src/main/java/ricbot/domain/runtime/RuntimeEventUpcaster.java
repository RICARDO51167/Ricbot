package ricbot.domain.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/** One deterministic event schema upgrade. Unknown versions are deliberately not skippable. */
public interface RuntimeEventUpcaster {
    int sourceVersion();
    int targetVersion();
    JsonNode upcast(String eventType, String payloadType, JsonNode payload);
}
