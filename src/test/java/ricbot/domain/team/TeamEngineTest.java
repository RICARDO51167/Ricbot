package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamEngineTest {

    @Test
    void createSessionAndTaskWritesInitialState(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);

        TeamSession session = engine.createSession("Coordinate approval and verifier gate");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Implement TeamEngine skeleton");

        assertEquals(TeamTaskState.PLANNING, session.state());
        assertEquals(TeamTaskState.CREATED, task.state());
        assertTrue(engine.getStatus(session.id()).contains(task.id()));
        assertTrue(engine.listEvents(session.id()).size() >= 2);
        assertTrue(Files.exists(workspace.resolve(".team").resolve(session.id()).resolve("whiteboard.md")));
    }

    @Test
    void producingToVerifyingPassMarksDone(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Verifier pass flow");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Produce result");

        task = engine.startProducing(task.id());
        assertEquals(TeamTaskState.PRODUCING, task.state());

        task = engine.submitWorkerResult(
                task.id(),
                "Implemented the state transition.",
                List.of(TeamArtifact.of(task.id(), "src/main/java/ricbot/domain/team/TeamEngine.java", "state machine"))
        );
        assertEquals("Implemented the state transition.", task.summary());

        task = engine.startVerifying(task.id());
        assertEquals(TeamTaskState.VERIFYING, task.state());

        task = engine.submitVerification(task.id(), VerificationResult.pass("targeted tests passed"));
        assertEquals(TeamTaskState.DONE, task.state());
        assertEquals(VerificationResult.Status.PASS, task.verificationResult().status());
        assertTrue(engine.getStatus(session.id()).contains("DONE"));
    }

    @Test
    void verifierRejectMovesTaskToRevisingAndRecordsRevisionRequest(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Verifier reject flow");
        TeamTask task = engine.createTask(session.id(), TeamRole.REVIEWER, "Review implementation");

        engine.startVerifying(task.id());
        task = engine.submitVerification(task.id(), VerificationResult.reject("missing TeamWhiteboard test"));

        assertEquals(TeamTaskState.REVISING, task.state());
        assertTrue(task.revisionRequest().contains("missing TeamWhiteboard test"));
        assertTrue(engine.contextSnapshot(session.id()).toString().contains("revisionRequests"));
    }

    @Test
    void verifierCanRequestHumanInput(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Human gate flow");
        TeamTask task = engine.createTask(session.id(), TeamRole.VERIFIER, "Check ambiguous result");

        engine.startVerifying(task.id());
        task = engine.submitVerification(task.id(), VerificationResult.needsHuman("requires approval from user"));

        assertEquals(TeamTaskState.NEEDS_HUMAN, task.state());
        assertEquals(TeamTaskState.NEEDS_HUMAN, engine.findSession(session.id()).state());
    }

    @Test
    void abortTaskMarksTaskAborted(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Abort flow");
        TeamTask task = engine.createTask(session.id(), TeamRole.EXPLORER, "Explore stale path");

        task = engine.abortTask(task.id());

        assertEquals(TeamTaskState.ABORTED, task.state());
        assertTrue(engine.listEvents(session.id()).stream().anyMatch(event -> event.type().equals("task_aborted")));
    }
}
