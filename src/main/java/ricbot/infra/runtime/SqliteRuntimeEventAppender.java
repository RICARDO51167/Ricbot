package ricbot.infra.runtime;

import ricbot.domain.runtime.RuntimeEvent;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

@FunctionalInterface
interface SqliteRuntimeEventAppender {
    RuntimeEvent append(String runId, long commitSequence, String type,
                        Instant occurredAt, Map<String, Object> payload) throws SQLException;
}
