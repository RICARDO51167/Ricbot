# Eval Learning Flow

Minimal sequence for converting eval failures into candidate experience.

Run a deterministic smoke eval:

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-smoke-workspace \
  --out target/eval-smoke-artifacts
```

Learn from the run artifact:

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval learn \
  --run target/eval-smoke-artifacts/<run-id> \
  --workspace target/demo-workspace \
  --include-xfail
```

Expected output:

```text
ricbot eval learn
candidates_generated:
added:
skipped_duplicate:
- exp_... [TEST_POLICY] ... sourceRef=eval:<runId>:<caseId>
candidates_file: .../experience/candidates.jsonl
```

Review flow:

```text
/experience list
/experience show exp_...
/experience verify exp_...
/experience promote exp_...
```

Safety rules:

- `eval learn` only writes candidate experience.
- It does not verify, promote, or add entries to prompt context.
- Re-running on the same `sourceRef + type + title` skips duplicates.
