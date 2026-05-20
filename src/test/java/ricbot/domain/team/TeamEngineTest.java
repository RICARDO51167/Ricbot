package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(Files.exists(workspace.resolve(".team").resolve(session.id()).resolve("session.json")));
        assertTrue(Files.exists(workspace.resolve(".team").resolve(session.id()).resolve("tasks.jsonl")));
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
        assertTrue(Files.exists(workspace.resolve(".team").resolve(session.id()).resolve("verification.jsonl")));
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
        assertTrue(engine.listEvents(session.id()).stream().anyMatch(event -> event.type().equals(TeamEvent.TASK_ABORTED)));
    }

    @Test
    void stateTransitionsPersistAndCanResumeFromNewEngine(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Persistent session");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Persist task");
        engine.startProducing(task.id());
        engine.startVerifying(task.id());
        engine.submitVerification(task.id(), VerificationResult.reject("needs revision"));

        TeamEngine restored = new TeamEngine(workspace);
        TeamSession resumed = restored.resumeSession(session.id());

        assertEquals(session.id(), resumed.id());
        assertTrue(restored.getStatus(session.id()).contains("REVISING"));
        assertTrue(restored.listEvents(session.id()).stream().anyMatch(event -> event.type().equals(TeamEvent.TEAM_RESUMED)));
    }

    @Test
    void archiveSessionIsNotLatestActive(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession archived = engine.createSession("Archive me");
        engine.archiveSession(archived.id());
        TeamSession active = engine.createSession("Keep active");

        TeamSession latest = new TeamEngine(workspace).loadLatestActiveSession();

        assertEquals(active.id(), latest.id());
        assertTrue(engine.isArchived(archived.id()));
    }

    @Test
    void createVerifierTaskFromDiffReviewCreatesVerifierTask(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Verifier task flow");

        TeamTask verifier = engine.createVerifierTaskFromDiffReview(session.id(), "DiffReview risk=MEDIUM suggestedTests=TeamEngineTest");

        assertEquals(TeamRole.VERIFIER, verifier.role());
        assertTrue(verifier.goal().contains("DiffReview"));
        assertTrue(engine.whiteboard(session.id()).readSummary().contains("Verifier task prepared"));
    }

    @Test
    void autoVerifyPassMarksTaskDone(@TempDir Path workspace) {
        TraceStore traceStore = new TraceStore(workspace);
        TeamEngine engine = new TeamEngine(workspace, traceStore);
        TeamSession session = engine.createSession("Auto verify pass");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Implement safe change");
        task = engine.submitWorkerResult(task.id(), "Implemented safe change.", List.of());

        task = engine.autoVerify(task.id(), verificationInput(task, List.of("DiffReview risk=LOW"), List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"), List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test")));

        assertEquals(TeamTaskState.DONE, task.state());
        assertEquals(VerificationResult.Status.PASS, task.verificationResult().status());
        assertTrue(engine.verificationReports(task.id()).toString().contains("PASS"));
        assertTrue(engine.whiteboard(session.id()).readSummary().contains("RiskLevel: LOW"));
        List<ricbot.domain.trace.TraceEvent> events = traceStore.loadEvents(traceStore.traceIdForSession(session.id()));
        assertTrue(events.stream().anyMatch(event -> event.type() == TraceEventType.VERIFICATION_RESULT), events.toString());
        assertTrue(events.stream().anyMatch(event -> event.type() == TraceEventType.TEAM_EVENT), events.toString());
    }

    @Test
    void autoVerifyRejectMovesTaskToRevising(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Auto verify reject");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Implement missing tests");
        task = engine.submitWorkerResult(task.id(), "Implemented change.", List.of());

        task = engine.autoVerify(task.id(), verificationInput(task, List.of("DiffReview risk=MEDIUM suspiciousChanges: write_file"), List.of("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test"), List.of()));

        assertEquals(TeamTaskState.REVISING, task.state());
        assertEquals(VerificationResult.Status.REJECT, task.verificationResult().status());
        assertFalse(task.verificationResult().missingTests().isEmpty());
        assertTrue(task.verificationResult().reasons().contains("suggested tests were not executed"));
        assertTrue(task.revisionRequest().contains("Revision requested"));
    }

    @Test
    void autoVerifyNeedsHumanForHighRisk(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Auto verify needs human");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Modify approval service");
        task = engine.submitWorkerResult(task.id(), "Changed approval logic.", List.of());

        task = engine.autoVerify(task.id(), verificationInput(task, List.of("src/main/java/ricbot/domain/security/ApprovalService.java [risk=HIGH]"), List.of(), List.of()));

        assertEquals(TeamTaskState.NEEDS_HUMAN, task.state());
        assertEquals(VerificationResult.Status.NEEDS_HUMAN, task.verificationResult().status());
        assertTrue(task.verificationResult().humanApprovalRequired());
    }

    @Test
    void runWorkerMovesTaskToVerifyingAndRecordsReport(@TempDir Path workspace) {
        TraceStore traceStore = new TraceStore(workspace);
        TeamEngine engine = new TeamEngine(workspace, traceStore);
        TeamSession session = engine.createSession("Run worker flow");
        TeamTask task = engine.createTask(session.id(), TeamRole.EXPLORER, "Explore worker execution");

        WorkerExecutionResult result = engine.runWorker(task.id(), workerInput(task, workspace.toString(), List.of(), List.of()));
        TeamTask updated = engine.findTask(task.id());

        assertEquals(TeamTaskState.VERIFYING, updated.state());
        assertEquals(TeamRole.EXPLORER, result.role());
        assertTrue(updated.summary().contains("Explorer summarized"), updated.summary());
        assertTrue(engine.workerReports(task.id()).toString().contains("Explorer summarized"));
        assertTrue(engine.whiteboard(session.id()).readSummary().contains("Worker execution"));
        assertTrue(traceStore.loadEvents(traceStore.traceIdForSession(session.id())).stream().anyMatch(event -> event.type() == TraceEventType.WORKER_FINISHED));
    }

    @Test
    void runVerifierPassMovesTaskDone(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Run verifier pass");
        TeamTask task = engine.createTask(session.id(), TeamRole.EXPLORER, "Explore safe change");
        engine.submitWorkerResult(task.id(), "Implemented safe change.", List.of());

        engine.runVerifier(task.id(), workerInput(
                task,
                workspace.toString(),
                List.of("DiffReview risk=LOW"),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test")
        ));
        TeamTask updated = engine.findTask(task.id());

        assertEquals(TeamTaskState.DONE, updated.state());
        assertEquals(VerificationResult.Status.PASS, updated.verificationResult().status());
        assertTrue(engine.workerReports(task.id()).toString().contains("Verifier accepted"));
    }

    @Test
    void runVerifierRejectMovesTaskRevising(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Run verifier reject");
        TeamTask task = engine.createTask(session.id(), TeamRole.EXPLORER, "Explore missing tests");
        engine.submitWorkerResult(task.id(), "Implemented change.", List.of());

        engine.runVerifier(task.id(), workerInput(
                task,
                workspace.toString(),
                List.of("DiffReview risk=MEDIUM suspiciousChanges: filesystem"),
                List.of()
        ));
        TeamTask updated = engine.findTask(task.id());

        assertEquals(TeamTaskState.REVISING, updated.state());
        assertEquals(VerificationResult.Status.REJECT, updated.verificationResult().status());
        assertTrue(updated.revisionRequest().contains("Revision requested"));
    }

    private VerificationInput verificationInput(
            TeamTask task,
            List<String> diffReviews,
            List<String> suggestedTests,
            List<String> executedTests
    ) {
        return new VerificationInput(
                task.id(),
                task.goal(),
                task.summary(),
                diffReviews,
                "TaskSummary contains verification context",
                List.of(),
                suggestedTests,
                executedTests,
                List.of(),
                "whiteboard summary"
        );
    }

    private WorkerExecutionInput workerInput(
            TeamTask task,
            String workspacePath,
            List<String> findings,
            List<String> executedTests
    ) {
        return new WorkerExecutionInput(
                task.id(),
                task.sessionId(),
                task.role(),
                task.goal(),
                workspacePath,
                "TaskSummary contains verification context",
                List.of("src/main/java/ricbot/domain/team/TeamEngine.java"),
                List.of(),
                executedTests,
                "Implemented safe change.",
                findings,
                List.of(),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                List.of(),
                0d,
                ""
        );
    }
}
