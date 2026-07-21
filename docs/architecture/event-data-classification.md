# Event data classification

This table is the migration contract for pruning duplicate fact stores. The executable catalog is
`EventClassificationCatalog`; adding a typed Run, Trace, or Step Audit event without classifying it fails tests.

| Class | Meaning | Canonical target | Migration rule |
| --- | --- | --- | --- |
| Durable Fact | Required for recovery, authorization, state transitions, or audit | Versioned Journal event | Write synchronously before the unsafe boundary |
| Immutable Artifact | Potentially large evidence whose bytes must not change | Content-addressed Artifact | Journal stores the Artifact ID, digest, media type, and schema version |
| Read Model | Data derived for querying or display | Rebuildable projection | Never accept it as evidence or a recovery input |
| Diagnostic | Debugging and performance detail | Log, Trace, or OTLP | Failure or loss must not block Run commit |

## Current families

| Family | Classification boundary | Planned action |
| --- | --- | --- |
| Run Journal | Run/tool outcomes are Durable Facts; node transitions, model requests, and batch summaries are Diagnostic | Keep facts in Journal; send diagnostic detail to OTLP/logs |
| Trace | Approval, side-effect, ChangeSet, Workspace, policy, and implementation gate outcomes are Durable Facts; diff/test/eval payloads are Artifacts; repeated lifecycle records are Diagnostic | Migrate Trace-only facts to Journal and replace evidence payloads with Artifact references |
| Team Event | All declared Team events are Durable Facts, including Artifact references | Schema v1 is retained as the Team fact stream; records written before versioning load as v1 |
| Step Audit | Approval, applied tool, rejection, failure, ChangeSet link, and verification are Durable Facts; step bookkeeping is Diagnostic | Schema v2 writes only gate/evidence outcomes; legacy bookkeeping remains readable but is never appended or mirrored to Trace |
| Evidence | DiffEvidence, ExecutedTestEvidence, ApprovalEvidence, and VerificationEvidence are Immutable Artifacts | Preserve all four; never demote them to Trace |
| Console | ConsoleEvent, metrics, and run history are Read Models; explicit Console action audit is a Durable Fact | Rebuild Console views from Journal/Team/Worker state and delete Console Event Store |

Large values never move into a projection. The required flow is:

```text
evidence bytes -> immutable Artifact -> Journal Event containing the Artifact reference
display-only fields -> read model
diagnostic-only fields -> OTLP/logs
```

The former `.ricbot/console-events.jsonl` read model is no longer read or written. Console views are rebuilt from
Run Journal, Runtime Fact Journal, Team State, and Worker State. Legacy `.ricbot/console-actions.jsonl` data is
read-only; new Console action audits are versioned Runtime Fact events.
