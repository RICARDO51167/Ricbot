# Agent domain

The production entry point is `AgentRuntime`; `GraphRunService` is the CLI compatibility adapter for the same graph.
Execution follows `INGEST -> CONTEXT -> COMPACT? -> MODEL -> TOOLS/APPROVAL -> STEERING -> CONTEXT`.

Durable runtime state, events, sessions, tasks, approvals, side effects, deliveries, verifier reports, and checkpoints
are projections in `.ricbot/runtime.db`. Legacy filesystem state is read only by `LegacyRuntimeMigrator`, imported once,
validated, and archived. New code must not introduce a second journal, checkpoint, replay, or recovery protocol.
