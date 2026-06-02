# Verification Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backward-compatible structured evidence to `VerificationService` so verifier decisions can rely on test exit codes, changed-file risk metadata, and approval state instead of only text summaries.

**Architecture:** Keep the existing text-based `VerificationInput` API working and add an optional `VerificationEvidence` field. Implement small record types in `ricbot.domain.team`, then update `VerificationService` to prefer structured test evidence for suggested-test coverage and use structured diff/approval evidence for human gates.

**Tech Stack:** Java 17 records, JUnit 5, Maven wrapper (`sh ./mvnw`), existing `CommandRiskLevel`, `VerificationService`, and `VerificationServiceTest`.

---

## File Structure

- Create `src/main/java/ricbot/domain/team/ExecutedTestEvidence.java`
  - Holds one executed test command, exit code, status, and output summary.
  - Owns pass/fail normalization through `passed()`.
- Create `src/main/java/ricbot/domain/team/DiffEvidence.java`
  - Holds one changed file path, risk level, and boolean risk flags.
- Create `src/main/java/ricbot/domain/team/ApprovalEvidence.java`
  - Holds one approval request id, status, and risk level.
  - Owns pending/high-risk normalization through `pendingOrBlocked()` and `highRisk()`.
- Create `src/main/java/ricbot/domain/team/VerificationEvidence.java`
  - Aggregates test, diff, and approval evidence lists.
  - Normalizes null lists to empty immutable lists.
- Modify `src/main/java/ricbot/domain/team/VerificationInput.java`
  - Add optional `VerificationEvidence evidence`.
  - Preserve the existing canonical constructor signature by delegating to a new constructor with evidence.
  - Preserve `ofTask`.
- Modify `src/main/java/ricbot/domain/team/VerificationService.java`
  - Evaluate structured failed tests, missing suggested tests, diff risk, test deletion, and approval gates.
  - Keep existing text-only behavior when evidence is absent.
- Modify `src/test/java/ricbot/domain/team/VerificationServiceTest.java`
  - Add TDD tests for structured passing tests, failed tests, high-risk diffs, test deletion, pending approval, and null normalization.
  - Keep existing tests unchanged except for helper overloads.

---

### Task 1: Add Structured Evidence Records

**Files:**
- Create: `src/main/java/ricbot/domain/team/ExecutedTestEvidence.java`
- Create: `src/main/java/ricbot/domain/team/DiffEvidence.java`
- Create: `src/main/java/ricbot/domain/team/ApprovalEvidence.java`
- Create: `src/main/java/ricbot/domain/team/VerificationEvidence.java`
- Test: `src/test/java/ricbot/domain/team/VerificationServiceTest.java`

- [ ] **Step 1: Write failing null-normalization test**

Add this test near the top of `VerificationServiceTest`:

```java
    @Test
    void structuredEvidenceNormalizesNullListsAndStrings() {
        VerificationEvidence evidence = new VerificationEvidence(null, null, null);

        assertTrue(evidence.executedTests().isEmpty());
        assertTrue(evidence.changedFiles().isEmpty());
        assertTrue(evidence.approvals().isEmpty());

        ExecutedTestEvidence test = new ExecutedTestEvidence(null, 0, null, null);
        assertEquals("", test.command());
        assertEquals("", test.status());
        assertEquals("", test.outputSummary());
        assertTrue(test.passed());

        DiffEvidence diff = new DiffEvidence(null, null, true, false, false);
        assertEquals("", diff.path());
        assertEquals(CommandRiskLevel.LOW, diff.riskLevel());

        ApprovalEvidence approval = new ApprovalEvidence(null, null, null);
        assertEquals("", approval.requestId());
        assertEquals("", approval.status());
        assertEquals(CommandRiskLevel.LOW, approval.riskLevel());
        assertFalse(approval.pendingOrBlocked());
        assertFalse(approval.highRisk());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest#structuredEvidenceNormalizesNullListsAndStrings test
```

Expected: compilation fails because `VerificationEvidence`, `ExecutedTestEvidence`, `DiffEvidence`, and `ApprovalEvidence` do not exist.

- [ ] **Step 3: Create `ExecutedTestEvidence`**

Create `src/main/java/ricbot/domain/team/ExecutedTestEvidence.java`:

```java
package ricbot.domain.team;

import java.util.Locale;

public record ExecutedTestEvidence(
        String command,
        int exitCode,
        String status,
        String outputSummary
) {
    public ExecutedTestEvidence {
        command = clean(command);
        status = clean(status);
        outputSummary = clean(outputSummary);
    }

    public boolean passed() {
        if (exitCode != 0) {
            return false;
        }
        if (status.isBlank()) {
            return true;
        }
        String normalized = status.toLowerCase(Locale.ROOT);
        return normalized.equals("pass")
                || normalized.equals("passed")
                || normalized.equals("success")
                || normalized.equals("ok");
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
```

- [ ] **Step 4: Create `DiffEvidence`**

Create `src/main/java/ricbot/domain/team/DiffEvidence.java`:

```java
package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

public record DiffEvidence(
        String path,
        CommandRiskLevel riskLevel,
        boolean securitySensitive,
        boolean configChange,
        boolean testDeletion
) {
    public DiffEvidence {
        path = clean(path);
        riskLevel = riskLevel != null ? riskLevel : CommandRiskLevel.LOW;
    }

    public boolean highRisk() {
        return riskLevel == CommandRiskLevel.HIGH
                || riskLevel == CommandRiskLevel.BLOCKED
                || securitySensitive
                || configChange;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
```

- [ ] **Step 5: Create `ApprovalEvidence`**

Create `src/main/java/ricbot/domain/team/ApprovalEvidence.java`:

```java
package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

import java.util.Locale;

public record ApprovalEvidence(
        String requestId,
        String status,
        CommandRiskLevel riskLevel
) {
    public ApprovalEvidence {
        requestId = clean(requestId);
        status = clean(status);
        riskLevel = riskLevel != null ? riskLevel : CommandRiskLevel.LOW;
    }

    public boolean pendingOrBlocked() {
        String normalized = status.toLowerCase(Locale.ROOT);
        return normalized.contains("pending")
                || normalized.contains("blocked")
                || normalized.contains("requires_approval")
                || normalized.contains("needs_approval")
                || normalized.contains("需要审批");
    }

    public boolean highRisk() {
        return riskLevel == CommandRiskLevel.HIGH || riskLevel == CommandRiskLevel.BLOCKED;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
```

- [ ] **Step 6: Create `VerificationEvidence`**

Create `src/main/java/ricbot/domain/team/VerificationEvidence.java`:

```java
package ricbot.domain.team;

import java.util.List;

public record VerificationEvidence(
        List<ExecutedTestEvidence> executedTests,
        List<DiffEvidence> changedFiles,
        List<ApprovalEvidence> approvals
) {
    public VerificationEvidence {
        executedTests = copy(executedTests);
        changedFiles = copy(changedFiles);
        approvals = copy(approvals);
    }

    public boolean present() {
        return !executedTests.isEmpty() || !changedFiles.isEmpty() || !approvals.isEmpty();
    }

    private static <T> List<T> copy(List<T> values) {
        return values != null ? List.copyOf(values) : List.of();
    }
}
```

- [ ] **Step 7: Run normalization test**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest#structuredEvidenceNormalizesNullListsAndStrings test
```

Expected: PASS.

- [ ] **Step 8: Commit task 1**

```bash
git add src/main/java/ricbot/domain/team/ExecutedTestEvidence.java \
        src/main/java/ricbot/domain/team/DiffEvidence.java \
        src/main/java/ricbot/domain/team/ApprovalEvidence.java \
        src/main/java/ricbot/domain/team/VerificationEvidence.java \
        src/test/java/ricbot/domain/team/VerificationServiceTest.java
git commit -m "feat: add verification evidence records"
```

---

### Task 2: Extend VerificationInput Without Breaking Callers

**Files:**
- Modify: `src/main/java/ricbot/domain/team/VerificationInput.java`
- Modify: `src/test/java/ricbot/domain/team/VerificationServiceTest.java`

- [ ] **Step 1: Write failing constructor compatibility test**

Add this test to `VerificationServiceTest`:

```java
    @Test
    void verificationInputSupportsOptionalStructuredEvidence() {
        VerificationEvidence evidence = new VerificationEvidence(
                List.of(new ExecutedTestEvidence("./mvnw -q test", 0, "passed", "green")),
                List.of(),
                List.of()
        );

        VerificationInput withoutEvidence = input(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: docs/demo.md"),
                List.of("./mvnw -q test"),
                List.of("./mvnw -q test")
        );
        VerificationInput withEvidence = inputWithEvidence(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: docs/demo.md"),
                List.of("./mvnw -q test"),
                List.of(),
                evidence
        );

        assertEquals(null, withoutEvidence.evidence());
        assertEquals(evidence, withEvidence.evidence());
        assertEquals(List.of(), withEvidence.executedTests());
    }
```

Add this helper at the bottom of `VerificationServiceTest`, below the existing `input` helper:

```java
    private VerificationInput inputWithEvidence(
            String workerSummary,
            List<String> diffReviews,
            List<String> suggestedTests,
            List<String> executedTests,
            VerificationEvidence evidence
    ) {
        return new VerificationInput(
                "teamtask_test",
                "Verify task",
                workerSummary,
                diffReviews,
                "TaskSummary contains verification context",
                List.of(),
                suggestedTests,
                executedTests,
                List.of(),
                "whiteboard summary",
                evidence
        );
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest#verificationInputSupportsOptionalStructuredEvidence test
```

Expected: compilation fails because `VerificationInput` has no `evidence()` accessor and no constructor with `VerificationEvidence`.

- [ ] **Step 3: Modify `VerificationInput`**

Replace `src/main/java/ricbot/domain/team/VerificationInput.java` with:

```java
package ricbot.domain.team;

import java.util.List;

public record VerificationInput(
        String taskId,
        String taskGoal,
        String workerSummary,
        List<String> diffReviews,
        String taskSummary,
        List<String> approvalRecords,
        List<String> suggestedTests,
        List<String> executedTests,
        List<String> verifiedExperience,
        String teamWhiteboardSummary,
        VerificationEvidence evidence
) {
    public VerificationInput(
            String taskId,
            String taskGoal,
            String workerSummary,
            List<String> diffReviews,
            String taskSummary,
            List<String> approvalRecords,
            List<String> suggestedTests,
            List<String> executedTests,
            List<String> verifiedExperience,
            String teamWhiteboardSummary
    ) {
        this(
                taskId,
                taskGoal,
                workerSummary,
                diffReviews,
                taskSummary,
                approvalRecords,
                suggestedTests,
                executedTests,
                verifiedExperience,
                teamWhiteboardSummary,
                null
        );
    }

    public VerificationInput {
        taskId = clean(taskId);
        taskGoal = clean(taskGoal);
        workerSummary = clean(workerSummary);
        diffReviews = copy(diffReviews);
        taskSummary = clean(taskSummary);
        approvalRecords = copy(approvalRecords);
        suggestedTests = copy(suggestedTests);
        executedTests = copy(executedTests);
        verifiedExperience = copy(verifiedExperience);
        teamWhiteboardSummary = clean(teamWhiteboardSummary);
    }

    public static VerificationInput ofTask(TeamTask task) {
        return new VerificationInput(
                task != null ? task.id() : "",
                task != null ? task.goal() : "",
                task != null ? task.summary() : "",
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                null
        );
    }

    private static List<String> copy(List<String> values) {
        return values != null ? values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList() : List.of();
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
```

- [ ] **Step 4: Run constructor compatibility test**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest#verificationInputSupportsOptionalStructuredEvidence test
```

Expected: PASS.

- [ ] **Step 5: Run existing text-only verifier tests**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest test
```

Expected: PASS for all tests in `VerificationServiceTest`.

- [ ] **Step 6: Commit task 2**

```bash
git add src/main/java/ricbot/domain/team/VerificationInput.java \
        src/test/java/ricbot/domain/team/VerificationServiceTest.java
git commit -m "feat: attach evidence to verification input"
```

---

### Task 3: Use Structured Test Evidence

**Files:**
- Modify: `src/main/java/ricbot/domain/team/VerificationService.java`
- Modify: `src/test/java/ricbot/domain/team/VerificationServiceTest.java`

- [ ] **Step 1: Write failing passing-evidence test**

Add this test to `VerificationServiceTest`:

```java
    @Test
    void passingStructuredTestEvidenceCoversSuggestedTest() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: src/main/java/demo/App.java"),
                List.of("./mvnw -q -Dtest='demo.AppTest' test"),
                List.of(),
                new VerificationEvidence(
                        List.of(new ExecutedTestEvidence("./mvnw -q -Dtest='demo.AppTest' test", 0, "passed", "1 test passed")),
                        List.of(),
                        List.of()
                )
        ));

        assertEquals(VerificationResult.Status.PASS, result.status());
        assertTrue(result.missingTests().isEmpty());
    }
```

- [ ] **Step 2: Write failing failed-evidence precedence test**

Add this test to `VerificationServiceTest`:

```java
    @Test
    void failedStructuredTestEvidenceRejectsEvenWhenTextClaimsCoverage() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: src/main/java/demo/App.java"),
                List.of("./mvnw -q -Dtest='demo.AppTest' test"),
                List.of("./mvnw -q -Dtest='demo.AppTest' test"),
                new VerificationEvidence(
                        List.of(new ExecutedTestEvidence("./mvnw -q -Dtest='demo.AppTest' test", 1, "failed", "assertion failed")),
                        List.of(),
                        List.of()
                )
        ));

        assertEquals(VerificationResult.Status.REJECT, result.status());
        assertTrue(result.reasons().contains("executed test failed"), result.reasons().toString());
        assertTrue(result.requiredActions().toString().contains("./mvnw -q -Dtest='demo.AppTest' test"),
                result.requiredActions().toString());
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest#passingStructuredTestEvidenceCoversSuggestedTest,VerificationServiceTest#failedStructuredTestEvidenceRejectsEvenWhenTextClaimsCoverage test
```

Expected: at least one failure because `VerificationService` still only uses text `executedTests`.

- [ ] **Step 4: Update `VerificationService` test evidence logic**

In `src/main/java/ricbot/domain/team/VerificationService.java`, change the start of `verify` from:

```java
        List<String> suspiciousChanges = suspiciousChanges(safe);
        List<String> missingTests = missingTests(safe.suggestedTests(), safe.executedTests());
        List<String> requiredActions = new ArrayList<>();
        List<String> experienceActions = new ArrayList<>();
```

to:

```java
        VerificationEvidence evidence = safe.evidence();
        List<String> suspiciousChanges = suspiciousChanges(safe);
        List<String> failedStructuredTests = failedStructuredTests(evidence);
        List<String> executedCoverage = executedCoverage(safe, evidence);
        List<String> missingTests = missingTests(safe.suggestedTests(), executedCoverage);
        List<String> requiredActions = new ArrayList<>();
        List<String> experienceActions = new ArrayList<>();
```

After the existing empty `taskSummary` block, add:

```java
        if (!failedStructuredTests.isEmpty()) {
            reasons.add("executed test failed");
            requiredActions.add("Fix failing test evidence: " + String.join("; ", failedStructuredTests));
        }
```

Change the `else if` status condition from:

```java
        } else if (safe.workerSummary().isBlank() || safe.taskSummary().isBlank()
                || (!suspiciousChanges.isEmpty() && safe.executedTests().isEmpty())
                || !missingTests.isEmpty()
                || unresolvedBlocker) {
```

to:

```java
        } else if (safe.workerSummary().isBlank() || safe.taskSummary().isBlank()
                || !failedStructuredTests.isEmpty()
                || (!suspiciousChanges.isEmpty() && executedCoverage.isEmpty())
                || !missingTests.isEmpty()
                || unresolvedBlocker) {
```

Add these helper methods above `missingTests`:

```java
    private List<String> failedStructuredTests(VerificationEvidence evidence) {
        if (evidence == null) {
            return List.of();
        }
        List<String> failed = new ArrayList<>();
        for (ExecutedTestEvidence test : evidence.executedTests()) {
            if (test == null || test.passed()) {
                continue;
            }
            String command = test.command().isBlank() ? "<missing command>" : test.command();
            String summary = test.outputSummary().isBlank() ? "" : " (" + test.outputSummary() + ")";
            failed.add(command + summary);
        }
        return failed.stream().distinct().toList();
    }

    private List<String> executedCoverage(VerificationInput input, VerificationEvidence evidence) {
        if (evidence == null || evidence.executedTests().isEmpty()) {
            return input.executedTests();
        }
        return evidence.executedTests().stream()
                .filter(test -> test != null && test.passed() && !test.command().isBlank())
                .map(ExecutedTestEvidence::command)
                .distinct()
                .toList();
    }
```

- [ ] **Step 5: Run structured test evidence tests**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest#passingStructuredTestEvidenceCoversSuggestedTest,VerificationServiceTest#failedStructuredTestEvidenceRejectsEvenWhenTextClaimsCoverage test
```

Expected: PASS.

- [ ] **Step 6: Run all verifier tests**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit task 3**

```bash
git add src/main/java/ricbot/domain/team/VerificationService.java \
        src/test/java/ricbot/domain/team/VerificationServiceTest.java
git commit -m "feat: verify structured test evidence"
```

---

### Task 4: Use Structured Diff and Approval Evidence

**Files:**
- Modify: `src/main/java/ricbot/domain/team/VerificationService.java`
- Modify: `src/test/java/ricbot/domain/team/VerificationServiceTest.java`

- [ ] **Step 1: Write failing high-risk diff test**

Add this test to `VerificationServiceTest`:

```java
    @Test
    void highRiskStructuredDiffNeedsHuman() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: src/main/java/demo/App.java"),
                List.of("./mvnw -q -Dtest='demo.AppTest' test"),
                List.of(),
                new VerificationEvidence(
                        List.of(new ExecutedTestEvidence("./mvnw -q -Dtest='demo.AppTest' test", 0, "passed", "green")),
                        List.of(new DiffEvidence("src/main/java/ricbot/domain/security/ApprovalService.java", CommandRiskLevel.HIGH, true, false, false)),
                        List.of()
                )
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertEquals(CommandRiskLevel.HIGH, result.riskLevel());
        assertTrue(result.humanApprovalRequired());
        assertTrue(result.suspiciousChanges().toString().contains("ApprovalService.java"), result.suspiciousChanges().toString());
    }
```

- [ ] **Step 2: Write failing test deletion evidence test**

Add this test to `VerificationServiceTest`:

```java
    @Test
    void structuredTestDeletionNeedsHuman() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: src/test/java/demo/AppTest.java"),
                List.of("./mvnw -q -Dtest='demo.AppTest' test"),
                List.of(),
                new VerificationEvidence(
                        List.of(new ExecutedTestEvidence("./mvnw -q -Dtest='demo.AppTest' test", 0, "passed", "green")),
                        List.of(new DiffEvidence("src/test/java/demo/AppTest.java", CommandRiskLevel.LOW, false, false, true)),
                        List.of()
                )
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertTrue(result.requiredActions().toString().contains("Confirm test deletion"), result.requiredActions().toString());
    }
```

- [ ] **Step 3: Write failing pending approval evidence test**

Add this test to `VerificationServiceTest`:

```java
    @Test
    void pendingStructuredApprovalNeedsHuman() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: src/main/java/demo/App.java"),
                List.of("./mvnw -q -Dtest='demo.AppTest' test"),
                List.of(),
                new VerificationEvidence(
                        List.of(new ExecutedTestEvidence("./mvnw -q -Dtest='demo.AppTest' test", 0, "passed", "green")),
                        List.of(),
                        List.of(new ApprovalEvidence("approval_1", "pending", CommandRiskLevel.HIGH))
                )
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertTrue(result.reasons().contains("pending approval or blocked risk found"), result.reasons().toString());
    }
```

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest#highRiskStructuredDiffNeedsHuman,VerificationServiceTest#structuredTestDeletionNeedsHuman,VerificationServiceTest#pendingStructuredApprovalNeedsHuman test
```

Expected: failures because `VerificationService` does not yet read structured diff or approval evidence.

- [ ] **Step 5: Update `VerificationService` risk logic**

In `verify`, change:

```java
        boolean pendingApproval = containsAny(safe.approvalRecords(), "pending", "blocked", "risklevel=blocked", "risklevel=high", "需要审批", "approval_");
```

to:

```java
        boolean pendingApproval = containsAny(safe.approvalRecords(), "pending", "blocked", "risklevel=blocked", "risklevel=high", "需要审批", "approval_")
                || hasPendingApprovalEvidence(evidence);
```

Change:

```java
        boolean highRisk = highRiskDiff(safe.diffReviews())
                || containsAny(suspiciousChanges, "security", "approval", "provider", "agentloop", "toolregistry", "config", ".github/workflows", "ci workflow");
        boolean deletedTest = containsAny(safe.diffReviews(), "delete", "deleted") && containsAny(safe.diffReviews(), "test");
```

to:

```java
        boolean highRisk = highRiskDiff(safe.diffReviews())
                || hasHighRiskDiffEvidence(evidence)
                || containsAny(suspiciousChanges, "security", "approval", "provider", "agentloop", "toolregistry", "config", ".github/workflows", "ci workflow");
        boolean deletedTest = (containsAny(safe.diffReviews(), "delete", "deleted") && containsAny(safe.diffReviews(), "test"))
                || hasTestDeletionEvidence(evidence);
```

Add this line after `List<String> suspiciousChanges = suspiciousChanges(safe);`:

```java
        suspiciousChanges = merge(suspiciousChanges, structuredSuspiciousChanges(evidence));
```

Add these helper methods above `failedStructuredTests`:

```java
    private List<String> structuredSuspiciousChanges(VerificationEvidence evidence) {
        if (evidence == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (DiffEvidence diff : evidence.changedFiles()) {
            if (diff == null) {
                continue;
            }
            if (diff.highRisk() || diff.testDeletion()) {
                out.add("structured diff evidence: path=" + diff.path()
                        + ", riskLevel=" + diff.riskLevel()
                        + ", securitySensitive=" + diff.securitySensitive()
                        + ", configChange=" + diff.configChange()
                        + ", testDeletion=" + diff.testDeletion());
            }
        }
        return out;
    }

    private boolean hasHighRiskDiffEvidence(VerificationEvidence evidence) {
        return evidence != null && evidence.changedFiles().stream()
                .anyMatch(diff -> diff != null && diff.highRisk());
    }

    private boolean hasTestDeletionEvidence(VerificationEvidence evidence) {
        return evidence != null && evidence.changedFiles().stream()
                .anyMatch(diff -> diff != null && diff.testDeletion());
    }

    private boolean hasPendingApprovalEvidence(VerificationEvidence evidence) {
        return evidence != null && evidence.approvals().stream()
                .anyMatch(approval -> approval != null && (approval.pendingOrBlocked() || approval.highRisk()));
    }

    private List<String> merge(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>();
        out.addAll(first != null ? first : List.of());
        out.addAll(second != null ? second : List.of());
        return out.stream().distinct().toList();
    }
```

- [ ] **Step 6: Run structured risk tests**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest#highRiskStructuredDiffNeedsHuman,VerificationServiceTest#structuredTestDeletionNeedsHuman,VerificationServiceTest#pendingStructuredApprovalNeedsHuman test
```

Expected: PASS.

- [ ] **Step 7: Run all verifier tests**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest test
```

Expected: PASS.

- [ ] **Step 8: Commit task 4**

```bash
git add src/main/java/ricbot/domain/team/VerificationService.java \
        src/test/java/ricbot/domain/team/VerificationServiceTest.java
git commit -m "feat: gate verification on structured risk evidence"
```

---

### Task 5: Final Verification

**Files:**
- No new files.
- Verify all files touched by Tasks 1-4.

- [ ] **Step 1: Run focused verifier tests**

Run:

```bash
sh ./mvnw -q -Dtest=VerificationServiceTest test
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
sh ./mvnw -q test
```

Expected: PASS. Warnings from existing tests about invalid JSONL or `.team` not being a directory may appear; they are acceptable if Maven exits with code 0 and reports no failures.

- [ ] **Step 3: Review diff**

Run:

```bash
git diff -- src/main/java/ricbot/domain/team src/test/java/ricbot/domain/team/VerificationServiceTest.java
```

Expected: diff only includes structured evidence records, `VerificationInput`, `VerificationService`, and verifier tests.

- [ ] **Step 4: Commit any final cleanup**

If Step 3 shows uncommitted cleanup after previous task commits:

```bash
git add src/main/java/ricbot/domain/team src/test/java/ricbot/domain/team/VerificationServiceTest.java
git commit -m "test: verify structured evidence behavior"
```

If there is no uncommitted cleanup, do not create an empty commit.

