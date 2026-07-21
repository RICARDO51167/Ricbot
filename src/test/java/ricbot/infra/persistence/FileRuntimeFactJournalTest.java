package ricbot.infra.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileRuntimeFactJournalTest {
    @Test
    void appendIsDurableOrderedAndIdempotent(@TempDir Path workspace) {
        FileRuntimeFactJournal journal = new FileRuntimeFactJournal(workspace);
        RuntimeFactEvent first = journal.append(
                "event-1", "session-1", "approval.approved", "human", "approved", Map.of("id", "a1"), Instant.EPOCH);
        RuntimeFactEvent duplicate = journal.append(
                "event-1", "session-1", "approval.approved", "human", "duplicate", Map.of(), Instant.now());
        RuntimeFactEvent second = journal.append(
                "event-2", "session-1", "changeset.committed", "system", "committed", Map.of(), Instant.now());

        assertEquals(first, duplicate);
        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
        assertEquals(2, new FileRuntimeFactJournal(workspace).events("session-1", 0).size());
        assertEquals("event-2", journal.events("session-1", 1).get(0).eventId());
        assertEquals(RuntimeFactEvent.CURRENT_SCHEMA_VERSION, first.schemaVersion());
    }
}
