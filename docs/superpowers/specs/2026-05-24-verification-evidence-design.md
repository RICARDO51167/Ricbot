# Verification Evidence Design

## Context

Ricbot already has a `VerificationService` that can reject missing worker summaries, missing suggested tests, unresolved blockers, pending approvals, test deletion, and high-risk diffs. The current boundary mostly reads strings from `VerificationInput`: diff review text, task summary text, approval records, suggested tests, and executed tests.

That is useful for demos and backward compatibility, but it is weak evidence for a coding agent. A worker can produce text that looks convincing without tying the verifier decision to command exit codes, exact test commands, changed file metadata, or approval state.

## Goal

Add a structured evidence layer to verification while preserving the existing text-based inputs and existing callers.

The verifier should become stricter when structured evidence is present:

- A suggested test is covered only by passing test evidence.
- A failed test command rejects the task even if the text summary says it passed.
- High-risk diffs, test deletion, and pending high-risk approvals require a human gate.
- Existing text-only behavior continues to work when no structured evidence is supplied.

## Non-Goals

- Do not rewrite `TeamEngine`, `AgentCommands`, or report generation in this step.
- Do not remove existing `VerificationInput` text fields.
- Do not execute tests inside `VerificationService`; it only evaluates supplied evidence.
- Do not introduce persistence or artifact parsing in the verifier itself.

## Proposed Model

Add optional structured evidence to `VerificationInput`:

```java
public record VerificationEvidence(
        List<ExecutedTestEvidence> executedTests,
        List<DiffEvidence> changedFiles,
        List<ApprovalEvidence> approvals
) { }
```

Test evidence:

```java
public record ExecutedTestEvidence(
        String command,
        int exitCode,
        String status,
        String outputSummary
) { }
```

Diff evidence:

```java
public record DiffEvidence(
        String path,
        CommandRiskLevel riskLevel,
        boolean securitySensitive,
        boolean configChange,
        boolean testDeletion
) { }
```

Approval evidence:

```java
public record ApprovalEvidence(
        String requestId,
        String status,
        CommandRiskLevel riskLevel
) { }
```

The records should normalize null strings and null lists in their compact constructors, matching existing project style.

## Verification Rules

`VerificationService` should keep the current high-level status model: `PASS`, `REJECT`, and `NEEDS_HUMAN`.

When `VerificationEvidence` is present:

- Failed test evidence rejects:
  - Any `ExecutedTestEvidence` with non-zero `exitCode` or a non-passing `status` adds reason `executed test failed`.
  - Required action should include the failed command.
- Suggested test coverage uses passing evidence:
  - A suggested test is covered only when a passing `ExecutedTestEvidence.command` matches the existing command coverage rules.
  - Text in `VerificationInput.executedTests` remains a fallback only when no structured test evidence exists.
- Risk evidence gates human review:
  - Any `DiffEvidence` with `riskLevel == HIGH`, `securitySensitive == true`, or `configChange == true` marks high risk.
  - Any `DiffEvidence.testDeletion == true` marks test deletion.
  - Any `ApprovalEvidence` with pending/blocked status or high/blocked risk marks pending approval.
- Text evidence still participates:
  - Existing suspicious diff parsing, blocker detection, approval record parsing, and summary checks remain.
  - Structured evidence is additive and takes precedence where it is more precise.

When no `VerificationEvidence` is supplied, behavior should remain unchanged.

## Data Flow

Initial implementation:

1. Existing callers can keep constructing `VerificationInput` without evidence.
2. New tests construct `VerificationInput` with explicit `VerificationEvidence`.
3. `VerificationService` evaluates structured evidence first, then uses existing text fallbacks.

Future implementation:

1. `TaskSummaryService` or `TeamTaskReportService` can emit structured test evidence from recorded tool traces.
2. `DiffReviewService` / `ChangeSetService` can feed structured diff evidence.
3. `ApprovalService` can feed structured approval evidence.

## Error Handling

- Null evidence means no structured evidence.
- Empty evidence lists are valid but do not prove coverage.
- Unknown or blank test status is treated as failed unless `exitCode == 0` and status is blank. This avoids rejecting legacy command evidence that only has an exit code.
- Missing commands in structured test evidence do not cover suggested tests.

## Testing

Add focused unit tests in `VerificationServiceTest`:

- Passing structured test evidence covers a suggested test and passes.
- Failed structured test evidence rejects even if `executedTests` text includes the command.
- High-risk structured diff needs human review.
- Structured test deletion needs human review.
- Pending structured approval needs human review.
- Existing text-only tests still pass unchanged.

## Rollout

This is a backward-compatible internal API change. It should not change CLI output for text-only verification inputs. The visible behavior changes only when callers opt into structured evidence.
