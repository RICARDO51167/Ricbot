# Approval And DiffReview

Minimal sequence for showing safe file execution.

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  --config config/ricbot.config.json \
  --workspace target/demo-workspace \
  --session demo:approval
```

```text
请写入 notes/tasks/approval-demo.md，内容为一段 demo checklist。
```

Expected approval output:

```text
需要审批后才能执行
requestId: appr_...
riskLevel: MEDIUM
reasons:
affectedPaths:
```

Approve and resume the original pending tool call:

```text
/approve appr_...
```

Expected resumed output:

```text
已批准并恢复执行
DiffReview
changedFiles:
suspiciousChanges:
suggestedTests:
rollbackHint:
```

Reject path:

```text
/reject appr_...
```

Rejected requests cannot be consumed later. Approved pending calls are consumed once and cannot run twice.
