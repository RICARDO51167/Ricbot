# Self-improving Agent Loop Demo

This demo shows the current Ricbot loop end to end: context selection, safe tool execution, diff review, task notes, governed experience, verified context recall, promotion, and eval-driven learning.

## Setup

Build once and use a disposable workspace for the live demo.

```bash
sh ./mvnw -q -DskipTests package
mkdir -p target/demo-workspace
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  --config config/ricbot.config.json \
  --workspace target/demo-workspace \
  --session demo:self-improving-loop
```

Expected startup shape:

```text
交互模式
你：
```

## 1. User Task

Ask the agent to make a small file change that can produce a diff review.

```text
你：在 notes/tasks/demo-plan.md 写一个三行的 demo checklist，包含 context、approval、experience。
```

Expected result shape depends on approval settings:

```text
需要审批后才能执行
requestId: ...
riskLevel: MEDIUM
affectedPaths: notes/tasks/demo-plan.md
```

## 2. Context Recall

Inspect what context the agent selected for the task.

```text
你：/context --detail --sources
```

Expected output snippets:

```text
prompt_context_budget
task_state
tool_trace
verified_experience
sources
```

At this stage `verified_experience` may be empty. Candidate experience is never included here.

## 3. Risk Approval

The write operation is intercepted when approval is enabled and risk is MEDIUM/HIGH.

Expected approval block:

```text
需要审批后才能执行
requestId: appr_...
riskLevel: MEDIUM
reasons:
- file write
affectedPaths:
- notes/tasks/demo-plan.md
```

## 4. `/approve` Restores Execution

Approve the pending tool call using the request id from the previous step.

```text
你：/approve appr_...
```

Expected output snippets:

```text
已批准并恢复执行
tool: write_file
```

The original `write_file` arguments are restored through `ToolRegistry.executeApproved(...)`; `__approval_bypass` prevents recursive approval for this resumed execution.

## 5. DiffReview

A successful file write returns a diff review.

Expected output snippets:

```text
DiffReview
summary:
changedFiles: notes/tasks/demo-plan.md
riskLevel:
suspiciousChanges:
suggestedTests:
rollbackHint: rm notes/tasks/demo-plan.md
```

This is the handoff point from safe execution to reviewable engineering output.

## 6. `/summary`

Render the current task summary without writing a note.

```text
你：/summary
```

Expected output snippets:

```text
Task Summary
Goal
Changed Files
Diff Reviews
Suggested Tests
Rollback Hints
Next Actions
```

## 7. `/summary --write-note`

Persist the task summary into notes.

```text
你：/summary --write-note
```

Expected output snippets:

```text
summary note written
id: task-summary-...
path: notes/tasks/...
category: tasks
```

The note is written through `NoteService`, and `notes/index.json` is updated so the task note can be searched later.

## 8. `/experience extract`

Extract candidate experience from the current `TaskSummary`.

```text
你：/experience extract
```

Expected output snippets:

```text
experience extracted:
file: experience/candidates.jsonl
candidate experience
- exp_... [TEST_POLICY] ...
```

Only candidate experience is written. It is not used for context recall.

## 9. `/experience verify`

Manually verify a useful candidate before it can affect future context.

```text
你：/experience verify exp_...
```

Expected output snippets:

```text
experience verified
status: VERIFIED
source:
confidence:
```

This writes the verified entry to `experience/verified.jsonl`.

## 10. Verified Experience Enters `/context`

Ask a similar task and inspect context again.

```text
你：再改一个 notes/tasks/demo-followup.md，保持同样的 checklist 风格。
你：/context --detail --sources
```

Expected output snippets:

```text
verified_experience
count: 1
experience/verified.jsonl:exp_...
experience_type=TEST_POLICY
```

Candidate and rejected entries do not appear in this section.

## 11. `/experience promote`

Promote a verified, reviewed experience into project notes.

```text
你：/experience promote exp_...
```

Expected output snippets:

```text
experience promoted
promotedTo: notes/project/...
governanceNote:
```

Promotion is explicit and human-triggered. It updates project playbook notes but does not happen automatically after verification.

## 12. `eval learn` From Failed Artifacts

Run or reuse an eval artifact, then learn from failures or expected failures.

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-smoke-workspace \
  --out target/eval-smoke-artifacts

java -jar target/Ricbot-1.0-SNAPSHOT.jar eval learn \
  --run target/eval-smoke-artifacts/<run-id> \
  --workspace target/demo-workspace \
  --include-xfail
```

Expected output snippets:

```text
ricbot eval learn
candidates_generated:
added:
skipped_duplicate:
- exp_... [TEST_POLICY] Eval assertion_failed: golden-showcase-eval-learning-xfail sourceRef=eval:<runId>:golden-showcase-eval-learning-xfail
candidates_file: .../experience/candidates.jsonl
```

The output is still candidate-only. The review path remains:

```text
/experience list
/experience show exp_...
/experience verify exp_...
/experience promote exp_...
```

## Demo Claim

Ricbot closes the loop without silently mutating project knowledge:

```text
task -> context -> approval -> approved tool execution -> diff review
     -> task summary -> task note -> candidate experience
     -> human verification -> verified context recall
     -> explicit promotion -> eval-driven candidate learning
```
