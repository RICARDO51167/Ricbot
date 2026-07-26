package ricbot.domain.agent;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ContextAssembler {
    private final Path workspace;
    private final ContextBuilder contextBuilder;
    private final ContextSelectionService contextSelectionService;

    ContextAssembler(
            Path workspace,
            ContextBuilder contextBuilder,
            ContextSelectionService contextSelectionService
    ) {
        this.workspace = workspace;
        this.contextBuilder = contextBuilder;
        this.contextSelectionService = contextSelectionService;
    }

    AssembledContext buildInteractiveContext(
            InboundMessage msg,
            PreparedSessionContext prepared,
            int historyWindowMessages
    ) {
        ContextSelectionService.SelectionResult selection = contextSelectionService.select(
                new ContextSelectionService.SessionPreparedInputs(
                        prepared.sessionKey(),
                        prepared.archivedSummary(),
                        prepared.taskStateSnapshot(),
                        recentToolTrace(prepared.session()),
                        Map.of(),
                        workspaceContext(prepared.session())
                ),
                prepared.session().getMessages(),
                msg.getContent(),
                historyWindowMessages
        );

        String structuredContext = selection.bundle().render();
        String combinedContext = structuredContext;
        List<Map<String, Object>> history = selection.history();
        List<Map<String, Object>> initialMessages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId(),
                "",
                "user",
                selection.bundle()
        );
        Map<String, Object> contextTrace = buildContextTrace(
                prepared,
                selection,
                structuredContext,
                combinedContext,
                initialMessages
        );
        return new AssembledContext(combinedContext, selection.bundle(), history, initialMessages, contextTrace);
    }

    private Map<String, Object> buildContextTrace(
            PreparedSessionContext prepared,
            ContextSelectionService.SelectionResult selection,
            String structuredContext,
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
        trace.put("combined_context_chars", lengthOf(combinedContext));
        trace.put("prompt_context_budget", selection.bundle().budgetTrace());
        trace.put("context_quality", selection.bundle().qualityReport().toMap());
        return trace;
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

    private int lengthOf(String value) {
        return value != null ? value.length() : 0;
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
