package ricbot.domain.agent;

import ricbot.domain.agent.context.StructuredContextService;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.trace.TraceStore;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.tool.api.ToolRegistry;

/** Pre-assembled runtime components injected into the AgentLoop lifecycle coordinator. */
public record AgentRuntimeCore(
        ContextBuilder contextBuilder,
        AgentPersistenceComponents persistence,
        OpenTelemetryRuntime telemetryRuntime,
        TraceStore traceStore,
        SideEffectStore sideEffectStore,
        MemoryStore memoryStore,
        StructuredContextService contextCompaction,
        ApprovalService approvalService,
        SideEffectApplicationService sideEffectApplicationService,
        ToolRegistry tools,
        GraphRunService runner
) {
}
