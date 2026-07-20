package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistentTeamRuntimeTest {
    @Test
    void persistsIndependentWorkersAndAcknowledgedMessages(@TempDir Path workspace) {
        PersistentTeamRuntime runtime = new PersistentTeamRuntime(workspace);
        runtime.createWorker("team-1", "lead", TeamRole.LEADER, "", Map.of());
        runtime.createWorker("team-1", "dev", TeamRole.DEVELOPER, "lead", Map.of("skill", "java"));
        runtime.transition("team-1", "dev", WorkerSessionStatus.RUNNING, "task-1");

        TeamMailboxMessage message = runtime.send(
                "team-1", "lead", "dev", TeamMessageType.TASK, "task-1", Map.of("goal", "implement"));

        PersistentTeamRuntime restarted = new PersistentTeamRuntime(workspace);
        assertEquals(WorkerSessionStatus.RUNNING, restarted.worker("team-1", "dev").orElseThrow().status());
        assertEquals(List.of(message), restarted.inbox("team-1", "dev", 0, false));
        restarted.acknowledge("team-1", "dev", message.messageId());
        assertTrue(restarted.inbox("team-1", "dev", 0, false).isEmpty());
        assertEquals(List.of(message), restarted.inbox("team-1", "dev", 0, true));
    }

    @Test
    void supportsHandoffJoinAndDynamicBroadcast(@TempDir Path workspace) {
        PersistentTeamRuntime runtime = new PersistentTeamRuntime(workspace);
        runtime.createWorker("team", "one", TeamRole.DEVELOPER, "", Map.of());
        runtime.createWorker("team", "two", TeamRole.REVIEWER, "one", Map.of());
        runtime.createWorker("team", "three", TeamRole.TESTER, "one", Map.of());
        runtime.transition("team", "one", WorkerSessionStatus.RUNNING, "task");

        PersistentTeamRuntime.HandoffResult handoff = runtime.handoff(
                "team", "one", "two", "task", Map.of("checkpoint", "cp-1"));

        assertEquals(WorkerSessionStatus.PAUSED, handoff.source().status());
        assertEquals(WorkerSessionStatus.RUNNING, handoff.target().status());
        assertEquals(TeamMessageType.HANDOFF, runtime.inbox("team", "two", 0, false).get(0).type());
        assertEquals(2, runtime.broadcast("team", "one", TeamMessageType.CONTROL, Map.of("action", "sync")).size());

        runtime.transition("team", "one", WorkerSessionStatus.RUNNING, "task");
        runtime.transition("team", "one", WorkerSessionStatus.COMPLETED, "task");
        runtime.transition("team", "two", WorkerSessionStatus.COMPLETED, "task");
        assertTrue(runtime.join("team", List.of("one", "two")).successful());
        assertFalse(runtime.join("team", List.of("one", "three")).complete());
    }

    @Test
    void allocatesMonotonicMailboxSequences(@TempDir Path workspace) {
        PersistentTeamRuntime runtime = new PersistentTeamRuntime(workspace);
        runtime.createWorker("team", "receiver", TeamRole.DEVELOPER, "", Map.of());

        List<Long> sequences = java.util.stream.IntStream.range(0, 20).parallel()
                .mapToObj(index -> runtime.send("team", "sender", "receiver", TeamMessageType.PROGRESS,
                        "", Map.of("index", index)).sequence())
                .sorted().toList();

        assertEquals(java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList(), sequences);
    }
}
