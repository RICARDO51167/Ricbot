# Ricbot v6 A+B reliability closure

This release closes F01–F09 across cancellation, file writes, durable limits, tool schemas, scheduling, configuration diagnostics and release evidence.

## Accepted destructive migration risk (F10)

F10 is intentionally not fixed. When startup recognizes the known legacy v3 layout and proves that no Activation or resource lease is active, Ricbot logs a destructive-upgrade warning and deletes the legacy database, WAL and SHM before creating the current schema. Historical Runs, waits and UNKNOWN Effects in that database are permanently lost and cannot be recovered by Ricbot because no backup is created.

Unknown layouts, fingerprint mismatches, corrupt databases and databases with active leases continue to fail closed. Operators who need to retain legacy v3 history must make an external backup before starting this version.

## Operational notes

- `maxSupersteps` is a lifetime Run limit. Exhaustion is replayable and fails with `MAX_SUPERSTEPS_EXCEEDED`.
- `/run health` exposes scheduler success, failure, backoff and batch state.
- Explicit missing or malformed configuration fails with `CONFIG_NOT_FOUND` or `CONFIG_INVALID`.
- Golden eval baselines are tracked under `evals/baselines/golden/`; regeneration creates a candidate under `target/` and requires explicit promotion.
- File SHA checks coordinate Ricbot writers, but do not provide universal CAS against external writers that ignore Ricbot locking.
