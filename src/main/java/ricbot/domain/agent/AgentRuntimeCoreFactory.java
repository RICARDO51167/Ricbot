package ricbot.domain.agent;

import ricbot.domain.memory.Consolidator;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.session.SessionManager;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillsLoader;
import ricbot.domain.trace.TraceStore;
import ricbot.infra.config.Config;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/** Pure bootstrap factory for the stateful core shared by AgentLoop services. */
public final class AgentRuntimeCoreFactory {
    private AgentRuntimeCoreFactory() {
    }

    public static AgentRuntimeCore create(LLMProvider provider, Path rawWorkspace, String model,
                                          int contextWindowTokens, int maxToolResultChars,
                                          Config.WebToolsConfig webConfig, Config.ExecToolConfig execConfig,
                                          boolean restrictToWorkspace, SessionManager suppliedSessions,
                                          String timezone, List<String> disabledSkills, int sessionTtlMinutes) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        ContextBuilder contextBuilder = new ContextBuilder(workspace, timezone, disabledSkills);
        AgentPersistenceComponents persistence = AgentPersistenceFactory.create(workspace, suppliedSessions);
        OpenTelemetryRuntime telemetry = OpenTelemetryRuntime.fromEnvironment();
        RunEventSink events = RunEventSink.composite(persistence.journalStore(),
                new OpenTelemetryRunEventSink(telemetry.tracer("ricbot.agent", "1.0")));
        TraceStore traces = new TraceStore(workspace);
        SideEffectStore sideEffects = new AuditedSideEffectStore(persistence.sideEffectStore(), traces);
        MemoryStore memory = new MemoryStore(workspace);
        Consolidator compactor = new Consolidator(memory, provider, model, persistence.sessionManager(),
                contextWindowTokens, 4096);
        ApprovalService approvals = new ApprovalService(traces);
        SideEffectApplicationService sideEffectApplication = new SideEffectApplicationService(sideEffects, approvals);
        AutoCompact autoCompact = new AutoCompact(persistence.sessionManager(), compactor, sessionTtlMinutes);
        SpawnWorkerService workers = new SpawnWorkerService(provider, workspace, maxToolResultChars, model,
                webConfig, execConfig, restrictToWorkspace, disabledSkills);
        SkillsLoader skills = new SkillsLoader(workspace, null,
                disabledSkills != null ? new HashSet<>(disabledSkills) : new HashSet<>());
        SkillRouter skillRouter = new SkillRouter(skills,
                parseInt(System.getenv("RICBOT_SKILLS_MAX_SELECTED"), 3),
                parseInt(System.getenv("RICBOT_SKILLS_MAX_CHARS"), 12000));
        return new AgentRuntimeCore(contextBuilder, persistence, telemetry, events, traces, sideEffects,
                memory, compactor, approvals, sideEffectApplication, autoCompact, workers, skills,
                skillRouter, new ToolRegistry(), new AgentRunner(provider));
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
