package ricbot.domain.runtime;

import java.util.List;
import java.util.Map;

/** Peripheral transcript storage. Run state retains only this port's reference and cursor. */
public interface TranscriptPort {
    String initialize(String runId, List<Map<String, Object>> messages);
    void append(String runId, String entryId, Map<String, Object> message);
    List<Map<String, Object>> read(String runId);
    /** Reads only the prefix made visible by a committed RunState cursor. */
    default List<Map<String, Object>> read(String runId, long throughCursor) {
        List<Map<String, Object>> entries = read(runId);
        int limit = (int) Math.min(entries.size(), Math.max(0L, throughCursor));
        return List.copyOf(entries.subList(0, limit));
    }
    long size(String runId);
    String reference(String runId);

    /** Copies a durable prefix for Execution Replay without embedding transcript bodies in Run state. */
    default String copy(String sourceRunId, String targetRunId, long throughCursor) {
        return initialize(targetRunId, read(sourceRunId, throughCursor));
    }

    /** Removes an unpublished copied prefix after the enclosing Fork transaction fails. */
    void cleanup(String runId);
}
