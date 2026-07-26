# Agent domain

The production entry point is `AgentRuntime`; `AgentGraphFactory` supplies its concrete Agent graph nodes.
Execution follows `INGEST -> CONTEXT -> COMPACT? -> MODEL -> TOOLS/APPROVAL -> STEERING -> CONTEXT`.

Durable runtime state, events, sessions, tasks, approvals, side effects, deliveries, verifier reports, and checkpoints
are projections in Schema v2 `.ricbot/runtime.db`. Pre-v2 databases are archived as immutable, query-only sources;
they can never be resumed, signalled, cancelled, retried, or forked.
