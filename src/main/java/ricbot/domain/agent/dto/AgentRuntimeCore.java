package ricbot.domain.agent.dto;

import ricbot.domain.agent.interfacep.AgentInvocationRuntime;
import ricbot.domain.agent.ContextBuilder;
import ricbot.domain.agent.SideEffectApplicationService;
import ricbot.domain.agent.interfacep.SideEffectStore;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.trace.TraceStore;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.tool.api.ToolRegistry;
import ricbot.domain.runtime.AgentRuntime;

/** Pre-assembled runtime components injected into the AgentLoop lifecycle coordinator. */
public record AgentRuntimeCore(
        ContextBuilder contextBuilder,
        AgentPersistenceComponents persistence,
        OpenTelemetryRuntime telemetryRuntime,
        TraceStore traceStore,
        SideEffectStore sideEffectStore,
        MemoryStore memoryStore,
        ApprovalService approvalService,
        SideEffectApplicationService sideEffectApplicationService,
        ToolRegistry tools,
        AgentRuntime agentRuntime,
        AgentInvocationRuntime runner
) {
}
