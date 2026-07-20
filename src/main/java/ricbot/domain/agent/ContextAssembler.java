package ricbot.domain.agent;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillRoutingContext;
import ricbot.domain.skill.SkillsLoader;
import ricbot.domain.subagent.SubAgentOrchestrator;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamSession;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ContextAssembler {
    private final Path workspace;
    private final ContextBuilder contextBuilder;
    private final SkillsLoader skillsLoader;
    private final SkillRouter skillRouter;
    private final ToolRegistry tools;
    private final ContextSelectionService contextSelectionService;

    ContextAssembler(
            Path workspace,
            ContextBuilder contextBuilder,
            SkillsLoader skillsLoader,
            SkillRouter skillRouter,
            ToolRegistry tools,
            ContextSelectionService contextSelectionService
    ) {
        this.workspace = workspace;
        this.contextBuilder = contextBuilder;
        this.skillsLoader = skillsLoader;
        this.skillRouter = skillRouter;
        this.tools = tools;
        this.contextSelectionService = contextSelectionService;
    }

    AssembledContext buildInteractiveContext(
            InboundMessage msg,
            PreparedSessionContext prepared,
            int historyWindowMessages
    ) {
        SkillRoutingContext skillRoutingContext = new SkillRoutingContext(
                workspace,
                msg.getChannel(),
                msg.getChatId(),
                msg.getContent(),
                tools != null ? tools.toolNames() : List.of(),
                msg.getMetadata(),
                Map.of()
        );
        SkillRouter.SelectionResult selected = skillRouter.selectAndRenderProgressive(skillRoutingContext);

        ContextSelectionService.SelectionResult selection = contextSelectionService.select(
                new ContextSelectionService.SessionPreparedInputs(
                        prepared.sessionKey(),
                        prepared.archivedSummary(),
                        prepared.taskStateSnapshot(),
                        recentToolTrace(prepared.session()),
                        SubAgentOrchestrator.resultsFromSession(prepared.session()),
                        teamContext(prepared.session()),
                        workspaceContext(prepared.session())
                ),
                prepared.session().getMessages(),
                msg.getContent(),
                historyWindowMessages
        );

        String structuredContext = selection.bundle().render();
        String skillContext = skillsContext(selected.renderedContext());
        String combinedContext = combineContext(structuredContext, skillContext);
        List<Map<String, Object>> history = selection.history();
        List<Map<String, Object>> initialMessages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId(),
                "",
                skillContext,
                "user",
                selection.bundle()
        );
        Map<String, Object> contextTrace = buildContextTrace(
                prepared,
                selection,
                selected,
                structuredContext,
                skillContext,
                combinedContext,
                initialMessages
        );
        return new AssembledContext(combinedContext, selection.bundle(), history, initialMessages, contextTrace);
    }

    private Map<String, Object> buildContextTrace(
            PreparedSessionContext prepared,
            ContextSelectionService.SelectionResult selection,
            SkillRouter.SelectionResult selectedSkills,
            String structuredContext,
            String skillContext,
            String combinedContext,
            List<Map<String, Object>> initialMessages
    ) {
        Map<String, Object> trace = new LinkedHashMap<>();
        List<Map<String, Object>> sessionMessages = prepared.session() != null ? prepared.session().getMessages() : List.of();
        trace.put("mode", "interactive");
        trace.put("session_key", prepared.sessionKey());
        trace.put("history_candidates", sessionMessages.size());
        trace.put("history_selected", selection.history().size());
        trace.put("initial_message_count", initialMessages != null ? initialMessages.size() : 0);
        trace.put("structured_context_chars", lengthOf(structuredContext));
        trace.put("skills_context_chars", lengthOf(skillContext));
        trace.put("combined_context_chars", lengthOf(combinedContext));
        trace.put("prompt_context_budget", selection.bundle().budgetTrace());
        trace.put("context_quality", selection.bundle().qualityReport().toMap());
        trace.put("skills", skillsTrace(selectedSkills, skillContext));
        return trace;
    }

    private Map<String, Object> teamContext(Session session) {
        Map<String, Object> existing = TeamEngine.contextFromSession(session);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        try {
            TeamEngine engine = new TeamEngine(workspace);
            TeamSession latest = engine.loadLatestActiveSession();
            return latest != null ? engine.contextSnapshot(latest.id()) : Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> workspaceContext(Session session) {
        if (session == null || session.getMetadata() == null) {
            return Map.of();
        }
        Object rawId = session.getMetadata().get(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY);
        String id = rawId != null ? String.valueOf(rawId).trim() : "";
        if (id.isBlank()) {
            return Map.of();
        }
        try {
            WorkspaceSession workspaceSession = new WorkspaceSessionStore(workspace).load(id);
            if (workspaceSession == null) {
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", workspaceSession.id());
            out.put("type", workspaceSession.type().name());
            out.put("status", workspaceSession.status().name());
            out.put("goal", workspaceSession.goal());
            out.put("path", workspaceSession.workspacePath());
            out.put("source", ".workspaces/" + workspaceSession.id() + "/session.json");
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> skillsTrace(SkillRouter.SelectionResult selectedSkills, String skillContext) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("context_chars", lengthOf(skillContext));
        if (selectedSkills == null) {
            trace.put("selected_count", 0);
            trace.put("decisions", List.of());
            return trace;
        }
        List<Map<String, Object>> decisions = new ArrayList<>();
        int selected = 0;
        for (SkillRouter.SkillDecision decision : selectedSkills.decisions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", decision.name());
            item.put("score", decision.score());
            item.put("always", decision.always());
            item.put("included", decision.included());
            item.put("reasons", decision.reasons());
            decisions.add(item);
            if (decision.included()) {
                selected++;
            }
        }
        trace.put("selected_count", selected);
        trace.put("decisions", decisions);
        return trace;
    }

    private int lengthOf(String value) {
        return value != null ? value.length() : 0;
    }

    private String combineContext(String... blocks) {
        StringBuilder sb = new StringBuilder();
        for (String block : blocks) {
            appendBlock(sb, block);
        }
        return sb.toString();
    }

    private String skillsContext(String loadedSkillsContext) {
        String summary = skillsLoader.buildSkillsSummary();
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append("## Skills Summary\n");
            sb.append("Only summary metadata is loaded by default. Use the read_skill tool to load a skill's full SKILL.md before following it, unless the skill is already included below.\n");
            sb.append(summary);
        }
        if (loadedSkillsContext != null && !loadedSkillsContext.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## Loaded Skills\n").append(loadedSkillsContext);
        }
        return sb.toString();
    }

    private void appendBlock(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        sb.append(value);
    }

    private List<Map<String, Object>> recentToolTrace(Session session) {
        if (session == null) {
            return List.of();
        }
        Object raw = session.getMetadata().get(SessionRuntimeKeys.TOOL_TRACE_KEY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(copyObjectMap(map));
            }
        }
        return out;
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        return ricbot.infra.common.JsonMapUtils.copyObjectMap(raw);
    }

    record AssembledContext(
            String combinedContext,
            PromptContextBundle bundle,
            List<Map<String, Object>> history,
            List<Map<String, Object>> initialMessages,
            Map<String, Object> contextTrace
    ) {
    }
}
