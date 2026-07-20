package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunEventReplayServiceTest {
    @Test
    void pagesFromAnExclusiveDisconnectCursor(@TempDir Path workspace) {
        FileRunJournalStore journal = new FileRunJournalStore(workspace);
        for (int sequence = 1; sequence <= 5; sequence++) {
            RunEventType type = sequence == 1 ? RunEventType.RUN_STARTED : RunEventType.NODE_STARTED;
            journal.append(RunEvent.create(sequence, "run", "session", sequence, type,
                    RunStatus.CREATED, null, Map.of()));
        }
        RunEventReplayService replay = new RunEventReplayService(journal);

        RunEventReplayService.ReplayBatch first = replay.replay("session", "run", 1, 2);
        RunEventReplayService.ReplayBatch second = replay.replay("session", "run", first.nextCursor(), 10);

        assertEquals(java.util.List.of(2L, 3L), first.events().stream().map(RunEvent::sequence).toList());
        assertFalse(first.caughtUp());
        assertEquals(java.util.List.of(4L, 5L), second.events().stream().map(RunEvent::sequence).toList());
        assertTrue(second.caughtUp());
    }
}
