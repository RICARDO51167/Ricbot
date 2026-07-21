package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.worker.WorkerState;
import ricbot.domain.worker.WorkerStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamEngineTest {

    @Test
    void workerLifecycleAndMailboxAreDurableAcrossEngineRestart(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("durable team");
        TeamTask task = engine.createTask(session.id(), TeamRole.EXPLORER, "inspect persistence");

        WorkerStore.StoredWorker worker = engine.startWorker(task.id(), TeamRole.EXPLORER);
        List<WorkerStore.MailboxMessage> assignment = engine.workerInbox(
                session.id(), worker.spec().workerId(), 0, false);
        assertEquals(WorkerStore.MessageKind.TASK, assignment.get(0).kind());
        engine.acknowledgeWorkerMessage(session.id(), worker.spec().workerId(), assignment.get(0).messageId());
        engine.completeWorker(worker.spec().workerId(), "inspection complete");

        TeamEngine restarted = new TeamEngine(workspace);
        WorkerStore.StoredWorker restored = restarted.workersForTask(task.id()).stream()
                .filter(value -> value.spec().workerId().equals(worker.spec().workerId()))
                .findFirst().orElseThrow();
        assertEquals(WorkerState.Status.COMPLETED, restored.state().status());
        assertTrue(restarted.joinWorkers(session.id(), List.of(worker.spec().workerId())).successful());
        String leaderId = restarted.workers(session.id()).stream()
                .filter(value -> TeamRole.LEADER.name().equals(value.spec().role()))
                .findFirst().orElseThrow().spec().workerId();
        assertTrue(restarted.workerInbox(session.id(), leaderId, 0, false).stream()
                .anyMatch(message -> message.kind() == WorkerStore.MessageKind.RESULT
                        && task.id().equals(message.correlationId())));
        assertTrue(Files.isDirectory(workspace.resolve(".ricbot").resolve("worker-runtime")));
    }

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
    void runDeveloperWorkerRecordsDeveloperPlan(@TempDir Path workspace) {
        TraceStore traceStore = new TraceStore(workspace);
        TeamEngine engine = new TeamEngine(workspace, traceStore);
        TeamSession session = engine.createSession("Run developer plan");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Edit README.md safely");

        WorkerExecutionResult result = engine.runWorker(task.id(), workerInput(task, workspace.toString(), List.of("README.md"), List.of()));

        assertEquals("PLANNED", result.status());
        assertTrue(result.developerPlan().toString().contains("README.md"), result.developerPlan().toString());
        assertTrue(result.changeSetRecommendation().contains("/change create"), result.changeSetRecommendation());
        assertTrue(engine.workerReports(task.id()).toString().contains("developerPlan="), engine.workerReports(task.id()).toString());
        assertTrue(traceStore.loadEvents(traceStore.traceIdForSession(session.id())).stream().anyMatch(event -> event.type() == TraceEventType.DEVELOPER_PLAN_CREATED));
    }

    @Test
    void createListFindApplyAndRejectImplementationSteps(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace, new TraceStore(workspace));
        TeamSession session = engine.createSession("Implementation steps");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Edit README.md safely");
        engine.runWorker(task.id(), workerInput(task, workspace.toString(), List.of("README.md"), List.of()));

        List<PendingImplementationStep> created = engine.createImplementationSteps(task.id());
        assertTrue(created.stream().anyMatch(step -> step.type() == ImplementationStepType.READ), created.toString());
        assertTrue(created.stream().anyMatch(step -> step.type() == ImplementationStepType.CREATE_CHANGESET), created.toString());

        List<PendingImplementationStep> listed = engine.listImplementationSteps(task.id());
        assertEquals(created.size(), listed.size());
        PendingImplementationStep read = listed.stream().filter(step -> step.type() == ImplementationStepType.READ).findFirst().orElseThrow();
        assertEquals(read.id(), engine.findImplementationStep(read.id()).id());

        PendingImplementationStep editBeforeRead = listed.stream().filter(step -> step.type() == ImplementationStepType.EDIT).findFirst().orElseThrow();
        StepGateResult blockedGate = engine.checkImplementationStepGate(editBeforeRead.id(), ImplementationStepGate.GateContext.empty());
        assertTrue(blockedGate.blocked(), blockedGate.toString());
        PendingImplementationStep blocked = engine.blockImplementationStep(editBeforeRead.id(), blockedGate);
        assertEquals(ImplementationStepStatus.BLOCKED, blocked.status());

        PendingImplementationStep applied = engine.applyImplementationStep(read.id());
        assertEquals(ImplementationStepStatus.APPLIED, applied.status());

        PendingImplementationStep rejectable = listed.stream().filter(step -> step.type() == ImplementationStepType.EDIT).findFirst().orElseThrow();
        PendingImplementationStep rejected = engine.rejectImplementationStep(rejectable.id());
        assertEquals(ImplementationStepStatus.REJECTED, rejected.status());
        assertTrue(Files.exists(workspace.resolve(".team").resolve(session.id()).resolve("implementation_steps.jsonl")));
        assertTrue(Files.exists(workspace.resolve(".team").resolve(session.id()).resolve("step_audit.jsonl")));
        assertTrue(engine.stepAuditByStep(read.id()).stream().anyMatch(record -> record.eventType() == StepAuditEventType.STEP_TOOL_APPLIED), engine.stepAuditByStep(read.id()).toString());
        assertTrue(engine.stepAuditByTask(task.id()).stream().anyMatch(record -> record.eventType() == StepAuditEventType.STEP_REJECTED), engine.stepAuditByTask(task.id()).toString());
        assertTrue(engine.stepAuditByTask(task.id()).stream().noneMatch(record -> !record.eventType().durableOutcome()), engine.stepAuditByTask(task.id()).toString());
        assertTrue(engine.renderStepTimeline(read.id()).contains("STEP_TOOL_APPLIED"));
        assertTrue(engine.contextSnapshot(session.id()).toString().contains("implementationSteps"), engine.contextSnapshot(session.id()).toString());
        assertTrue(engine.contextSnapshot(session.id()).toString().contains("implementationStepProgress"), engine.contextSnapshot(session.id()).toString());
        assertTrue(engine.contextSnapshot(session.id()).toString().contains("stepAuditSummary"), engine.contextSnapshot(session.id()).toString());
    }

    @Test
    void updateDraftEditStepCanBecomeReadyOrBlocked(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace, new TraceStore(workspace));
        TeamSession session = engine.createSession("Update draft step");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Update README.md");
        engine.runWorker(task.id(), workerInput(task, workspace.toString(), List.of("README.md"), List.of()));
        List<PendingImplementationStep> steps = engine.createImplementationSteps(task.id());
        PendingImplementationStep read = steps.stream().filter(step -> step.type() == ImplementationStepType.READ).findFirst().orElseThrow();
        PendingImplementationStep edit = steps.stream().filter(step -> step.type() == ImplementationStepType.EDIT).findFirst().orElseThrow();
        assertEquals(ImplementationStepStatus.DRAFT, edit.status());

        PendingImplementationStep blocked = engine.updateImplementationStep(edit.id(), new StepUpdateRequest(
                "", "old", "new", "", "", List.of(), "fill edit args"
        ));
        assertEquals(ImplementationStepStatus.BLOCKED, blocked.status());
        assertTrue(blocked.blockedReason().contains(read.id()), blocked.toString());

        engine.applyImplementationStep(read.id());
        PendingImplementationStep ready = engine.updateImplementationStep(edit.id(), new StepUpdateRequest(
                "", null, null, "", "", List.of(), "refresh after read"
        ));
        assertEquals(ImplementationStepStatus.READY, ready.status());
        assertTrue(ready.validationErrors().isEmpty(), ready.toString());
        assertEquals("user", ready.lastUpdatedBy());
        assertTrue(engine.stepAuditByStep(edit.id()).stream().noneMatch(record -> !record.eventType().durableOutcome()), engine.stepAuditByStep(edit.id()).toString());
    }

    @Test
    void appliedStepCannotBeUpdated(@TempDir Path workspace) {
        TeamEngine engine = new TeamEngine(workspace);
        TeamSession session = engine.createSession("Applied step immutable");
        TeamTask task = engine.createTask(session.id(), TeamRole.DEVELOPER, "Update README.md");
        engine.runWorker(task.id(), workerInput(task, workspace.toString(), List.of("README.md"), List.of()));
        PendingImplementationStep read = engine.createImplementationSteps(task.id()).stream()
                .filter(step -> step.type() == ImplementationStepType.READ)
                .findFirst()
                .orElseThrow();

        engine.applyImplementationStep(read.id());

        assertThrows(IllegalStateException.class, () -> engine.updateImplementationStep(read.id(), new StepUpdateRequest(
                "README.md", null, null, "", "", List.of(), "should fail"
        )));
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
