package ricbot.domain.agent;

import ricbot.domain.memory.Consolidator;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillsLoader;
import ricbot.domain.trace.TraceStore;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.tool.api.ToolRegistry;

/** Pre-assembled runtime components injected into the AgentLoop lifecycle coordinator. */
public record AgentRuntimeCore(
        ContextBuilder contextBuilder,
        AgentPersistenceComponents persistence,
        OpenTelemetryRuntime telemetryRuntime,
        RunEventSink runEventSink,
        TraceStore traceStore,
        SideEffectStore sideEffectStore,
        MemoryStore memoryStore,
        Consolidator consolidator,
        ApprovalService approvalService,
        SideEffectApplicationService sideEffectApplicationService,
        AutoCompact autoCompact,
        SpawnWorkerService spawnWorkers,
        SkillsLoader skillsLoader,
        SkillRouter skillRouter,
        ToolRegistry tools,
        AgentRunner runner
) {
}
