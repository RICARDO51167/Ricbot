package ricbot.integration.api.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ricbot.domain.agent.AgentRunController;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.config.ConfigDoctorReport;
import ricbot.domain.config.ConfigDoctorService;
import ricbot.domain.eval.ConsoleEvalSmokeService;
import ricbot.domain.eval.EvalRunDetail;
import ricbot.domain.eval.EvalRunSummary;
import ricbot.domain.eval.EvalRunsViewerService;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceSkillPromoter;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.experience.ExperienceStatus;
import ricbot.domain.security.ApprovalApplicationService;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;
import ricbot.domain.trace.TraceTimeline;
import ricbot.domain.trace.TraceViewerService;
import ricbot.infra.config.Config;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStatus;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.integration.api.RicbotApiAppContext;
import ricbot.integration.api.RicbotApiServer;
import ricbot.integration.mcp.MCPDiagnosticService;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class ConsoleController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final ConsolePostRateLimiter RATE_LIMITER = new ConsolePostRateLimiter(20, 10_000L, System::currentTimeMillis);
    private static final ConsoleRunRegistry RUN_REGISTRY = new ConsoleRunRegistry();
    private static final Map<Path, ConsoleEventBus> EVENT_BUSES = new ConcurrentHashMap<>();
    private static final ExecutorService RUN_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ricbot-console-run");
        thread.setDaemon(true);
        return thread;
    });

    private ConsoleController() {
    }

    public static void register(HttpServer server, RicbotApiAppContext appContext) {
        server.createContext("/api/console/runtime", runtimeHandler(appContext));
        server.createContext("/api/console/sessions/", sessionDetailHandler(appContext, "/api/console/sessions/"));
        server.createContext("/api/console/sessions", sessionsHandler(appContext));
        server.createContext("/api/console/events/search", eventSearchHandler(appContext));
        server.createContext("/api/console/metrics/summary", metricsSummaryHandler(appContext));
        server.createContext("/api/console/runs/", runStatusHandler(appContext, "/api/console/runs/"));
        server.createContext("/api/console/approvals/", approvalsHandler(appContext));
        server.createContext("/api/console/approvals/pending", approvalsPendingHandler(appContext));
        server.createContext("/api/console/changesets/", changeSetHandler(appContext, "/api/console/changesets/"));
        server.createContext("/api/console/changesets/recent", recentChangeSetsHandler(appContext));
        server.createContext("/api/console/workspace/tree", workspaceTreeHandler(appContext));
        server.createContext("/api/console/workspace/files/content", workspaceFileContentHandler(appContext));
        server.createContext("/api/console/workspace/search", workspaceSearchHandler(appContext));
        server.createContext("/console/api/runtime", runtimeHandler(appContext));
        server.createContext("/console/api/sessions/", sessionDetailHandler(appContext, "/console/api/sessions/"));
        server.createContext("/console/api/sessions", sessionsHandler(appContext));
        server.createContext("/console/api/events/search", eventSearchHandler(appContext));
        server.createContext("/console/api/metrics/summary", metricsSummaryHandler(appContext));
        server.createContext("/console/api/runs/", runStatusHandler(appContext, "/console/api/runs/"));
        server.createContext("/console/api/approvals/", approvalsHandler(appContext));
        server.createContext("/console/api/approvals/pending", approvalsPendingHandler(appContext));
        server.createContext("/console/api/changesets/", changeSetHandler(appContext, "/console/api/changesets/"));
        server.createContext("/console/api/changesets/recent", recentChangeSetsHandler(appContext));
        server.createContext("/console/api/workspace/tree", workspaceTreeHandler(appContext));
        server.createContext("/console/api/workspace/files/content", workspaceFileContentHandler(appContext));
        server.createContext("/console/api/workspace/search", workspaceSearchHandler(appContext));
        server.createContext("/console/api/health", healthHandler(appContext));
        server.createContext("/console/api/config-doctor", configDoctorHandler(appContext));
        server.createContext("/console/api/traces", tracesHandler(appContext));
        server.createContext("/console/api/team-reports", teamReportsHandler(appContext));
        server.createContext("/console/api/tools", toolsHandler(appContext));
        server.createContext("/console/api/mcp/diagnostics", mcpDiagnosticsHandler(appContext));
        server.createContext("/console/api/mcp", mcpHandler(appContext));
        server.createContext("/console/api/workspaces/", workspaceActionsHandler(appContext));
        server.createContext("/console/api/workspaces", workspacesHandler(appContext));
        server.createContext("/console/api/experiences/", experienceActionsHandler(appContext));
        server.createContext("/console/api/experiences", experiencesHandler(appContext));
        server.createContext("/console/api/approvals", approvalsHandler(appContext));
        server.createContext("/console/api/actions", actionsHandler(appContext));
        server.createContext("/console/api/release-check", releaseCheckHandler(appContext));
        server.createContext("/console/api/evals", evalsHandler(appContext));
        server.createContext("/console", pageHandler(appContext));
    }

    public static HttpHandler pageHandler(RicbotApiAppContext appContext) {
        return new ConsolePageHandler(appContext);
    }

    public static HttpHandler healthHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::health);
    }

    public static HttpHandler runtimeHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::runtime);
    }

    public static HttpHandler sessionsHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::sessions);
    }

    public static HttpHandler sessionDetailHandler(RicbotApiAppContext appContext, String prefix) {
        return new SessionDetailHandler(appContext, prefix);
    }

    public static HttpHandler runStatusHandler(RicbotApiAppContext appContext, String prefix) {
        return new RunStatusHandler(appContext, prefix);
    }

    public static HttpHandler eventSearchHandler(RicbotApiAppContext appContext) {
        return new EventSearchHandler(appContext);
    }

    public static HttpHandler metricsSummaryHandler(RicbotApiAppContext appContext) {
        return new MetricsSummaryHandler(appContext);
    }

    public static HttpHandler approvalsPendingHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::listApprovals);
    }

    public static HttpHandler recentChangeSetsHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::recentChangeSets);
    }

    public static HttpHandler changeSetHandler(RicbotApiAppContext appContext, String prefix) {
        return new ChangeSetHandler(appContext, prefix);
    }

    public static HttpHandler workspaceTreeHandler(RicbotApiAppContext appContext) {
        return new WorkspaceTreeHandler(appContext);
    }

    public static HttpHandler workspaceFileContentHandler(RicbotApiAppContext appContext) {
        return new WorkspaceFileContentHandler(appContext);
    }

    public static HttpHandler workspaceSearchHandler(RicbotApiAppContext appContext) {
        return new WorkspaceSearchHandler(appContext);
    }

    public static HttpHandler configDoctorHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::configDoctor);
    }

    public static HttpHandler tracesHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::traces);
    }

    public static HttpHandler teamReportsHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::teamReports);
    }

    public static HttpHandler toolsHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::tools);
    }

    public static HttpHandler mcpHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::mcp);
    }

    public static HttpHandler mcpDiagnosticsHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::mcpDiagnostics);
    }

    public static HttpHandler workspacesHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::workspaces);
    }

    public static HttpHandler workspaceActionsHandler(RicbotApiAppContext appContext) {
        return new WorkspaceActionHandler(appContext);
    }

    public static HttpHandler experiencesHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::experiences);
    }

    public static HttpHandler experienceActionsHandler(RicbotApiAppContext appContext) {
        return new ExperienceActionHandler(appContext);
    }

    public static HttpHandler approvalsHandler(RicbotApiAppContext appContext) {
        return new ApprovalHandler(appContext);
    }

    public static HttpHandler actionsHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::actions);
    }

    public static HttpHandler releaseCheckHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::releaseCheck);
    }

    public static HttpHandler evalsHandler(RicbotApiAppContext appContext) {
        return new EvalRunsHandler(appContext);
    }

    private static Map<String, Object> health(RicbotApiAppContext appContext) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("time", Instant.now().toString());
        body.put("workspace", appContext.getWorkspace().toString());
        body.put("model", appContext.getModelName());
        body.put("bindHost", appContext.getBindHost());
        body.put("readonly", true);
        body.put("warning", appContext.isConsoleExposedBeyondLoopback()
                ? "Console is served on a non-loopback API bind host. Keep api.bearer_token enabled and do not expose it to the public internet."
                : "");
        return body;
    }

    private static Map<String, Object> runtime(RicbotApiAppContext appContext) {
        Config config = appContext.getConfig();
        Config.AgentDefaults defaults = config != null && config.getAgents() != null
                ? config.getAgents().getDefaults()
                : new Config.AgentDefaults();
        String configuredModel = defaults != null ? clean(defaults.getModel()) : "";
        String providerName = !configuredModel.isBlank() && config != null ? clean(config.getProviderName(configuredModel)) : "";
        Config.ProviderConfig provider = !providerName.isBlank() && config != null ? config.getProvider(configuredModel) : null;
        boolean modelConfigured = !configuredModel.isBlank()
                && !providerName.isBlank()
                && provider != null
                && provider.getApiKey() != null
                && !provider.getApiKey().isBlank();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appName", "Ricbot");
        body.put("mode", "backend");
        body.put("modelConfigured", modelConfigured);
        body.put("provider", modelConfigured ? providerName : null);
        body.put("model", modelConfigured ? configuredModel : null);
        body.put("workspace", appContext.getWorkspace().toString());
        body.put("version", version());
        body.put("readonly", true);
        body.put("configPath", appContext.getConfigPath().toString());
        return body;
    }

    private static Map<String, Object> sessions(RicbotApiAppContext appContext) {
        SessionManager manager = appContext.getAgentLoop() != null && appContext.getAgentLoop().getSessions() != null
                ? appContext.getAgentLoop().getSessions()
                : new SessionManager(appContext.getWorkspace());
        List<Map<String, Object>> items = manager.listSessions().stream()
                .limit(50)
                .map(ConsoleController::sessionSummary)
                .toList();
        return Map.of(
                "mode", "backend",
                "items", items
        );
    }

    private static Map<String, Object> sessionDetail(RicbotApiAppContext appContext, String sessionId) {
        Session session = sessionManager(appContext).find(sessionId)
                .orElseThrow(() -> new ConsoleNotFoundException("session not found: " + sessionId));
        List<Map<String, Object>> messages = session.getMessages().stream()
                .map(ConsoleController::sanitizeMap)
                .toList();
        List<Map<String, Object>> runEvents = runEventsFromSession(session);
        List<Map<String, Object>> toolCalls = toolCallsFromMessages(sessionId, messages);
        List<Map<String, Object>> approvals = approvalsForSession(appContext, sessionId);
        List<Map<String, Object>> changeSets = changeSetsForSession(appContext, sessionId);
        TraceTimeline traceTimeline = traceTimelineForSession(appContext, sessionId);
        List<Map<String, Object>> traceEvents = traceTimeline.events().stream()
                .map(event -> sanitizeMap(event.toMap()))
                .toList();
        Map<String, Object> runtime = runtime(appContext);
        Map<String, Object> metadata = sanitizeMap(session.getMetadata());
        metadata.put("createdAt", session.getCreatedAt().toString());
        metadata.put("updatedAt", session.getUpdatedAt().toString());
        metadata.put("lastConsolidated", session.getLastConsolidated());
        metadata.put("messageCount", messages.size());
        metadata.put("trace", sanitizeMap(traceTimeline.toMap()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getKey());
        out.put("title", session.getKey());
        out.put("workspace", appContext.getWorkspace().toString());
        out.put("status", sessionStatus(approvals, traceTimeline));
        out.put("model", runtime.get("model"));
        out.put("provider", runtime.get("provider"));
        out.put("messages", messages);
        out.put("runEvents", runEvents);
        out.put("toolCalls", toolCalls);
        out.put("traceEvents", traceEvents);
        out.put("approvalEvents", approvals);
        out.put("changeSets", changeSets);
        out.put("metadata", metadata);
        return out;
    }

    private static List<Map<String, Object>> sessionTimeline(RicbotApiAppContext appContext, String sessionId) {
        return sessionTimeline(appContext, sessionId, "", "");
    }

    private static List<Map<String, Object>> sessionTimeline(RicbotApiAppContext appContext, String sessionId, String categoryFilter) {
        return sessionTimeline(appContext, sessionId, categoryFilter, "");
    }

    private static List<Map<String, Object>> sessionTimeline(RicbotApiAppContext appContext, String sessionId, String categoryFilter, String runIdFilter) {
        Map<String, Object> detail = sessionDetail(appContext, sessionId);
        List<Map<String, Object>> events = new java.util.ArrayList<>();
        List<Map<String, Object>> messages = castMapList(detail.get("messages"));
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> message = messages.get(i);
            String role = stringValue(message.get("role"));
            String type = switch (role) {
                case "assistant" -> "assistant_message";
                case "tool" -> "tool_message";
                case "system" -> "system_message";
                default -> "user_message";
            };
            events.add(timelineEvent(
                    sessionId + "-message-" + i,
                    type,
                    stringValue(message.get("timestamp")),
                    messageTitle(role),
                    contentSummary(message.get("content")),
                    "OK",
                    message
            ));
        }
        for (int i = 0; i < castMapList(detail.get("runEvents")).size(); i++) {
            Map<String, Object> runEvent = castMapList(detail.get("runEvents")).get(i);
            events.add(timelineEvent(
                    stableRunEventId(sessionId, runEvent, i),
                    "run_event",
                    runEventTime(runEvent, stringValue(detail.get("updatedAt"))),
                    runEventTitle(runEvent),
                    runEventSummary(runEvent),
                    runEventStatus(runEvent),
                    runEvent
            ));
        }
        for (Map<String, Object> toolCall : castMapList(detail.get("toolCalls"))) {
            events.add(timelineEvent(
                    safeEventId(sessionId, "tool", toolCall.get("id")),
                    "tool_call",
                    stringValue(toolCall.get("createdAt")),
                    "Tool: " + stringValue(toolCall.get("toolName")),
                    stringValue(toolCall.getOrDefault("summary", toolCall.get("toolName"))),
                    stringValue(toolCall.getOrDefault("status", "UNKNOWN")),
                    toolCall
            ));
        }
        for (Map<String, Object> approval : castMapList(detail.get("approvalEvents"))) {
            events.add(timelineEvent(
                    safeEventId(sessionId, "approval", approval.get("requestId")),
                    "approval_event",
                    stringValue(approval.get("createdAt")),
                    "Approval: " + stringValue(approval.get("requestId")),
                    approvalSummary(approval),
                    stringValue(approval.getOrDefault("status", "PENDING")),
                    approval
            ));
        }
        for (Map<String, Object> traceEvent : castMapList(detail.get("traceEvents"))) {
            events.add(timelineEvent(
                    safeEventId(sessionId, "trace", traceEvent.get("timestamp") + "-" + traceEvent.get("type")),
                    "trace_event",
                    stringValue(traceEvent.get("timestamp")),
                    stringValue(traceEvent.getOrDefault("title", traceEvent.get("type"))),
                    stringValue(traceEvent.getOrDefault("detail", "")),
                    stringValue(traceEvent.getOrDefault("severity", "INFO")),
                    traceEvent
            ));
        }
        for (Map<String, Object> changeSet : castMapList(detail.get("changeSets"))) {
            events.add(timelineEvent(
                    safeEventId(sessionId, "changeset", changeSet.get("id")),
                    "changeset_event",
                    stringValue(changeSet.get("updatedAt")),
                    "ChangeSet: " + stringValue(changeSet.get("id")),
                    stringValue(changeSet.getOrDefault("diffSummary", "")),
                    stringValue(changeSet.getOrDefault("status", "DRAFT")),
                    changeSet
            ));
        }
        for (ConsoleEvent event : consoleEventBus(appContext).listBySession(sessionId, "", "", 200)) {
            events.add(event.toMap());
        }
        return new ConsoleTimelineAssembler().assemble(sessionId, events, categoryFilter, runIdFilter);
    }

    private static Map<String, Object> sessionEvents(RicbotApiAppContext appContext, String sessionId, String after) {
        return sessionEvents(appContext, sessionId, after, "");
    }

    private static Map<String, Object> sessionEvents(RicbotApiAppContext appContext, String sessionId, String after, String categoryFilter) {
        return sessionEvents(appContext, sessionId, after, categoryFilter, "");
    }

    private static Map<String, Object> sessionEvents(RicbotApiAppContext appContext, String sessionId, String after, String categoryFilter, String runIdFilter) {
        List<Map<String, Object>> timeline = sessionTimeline(appContext, sessionId, categoryFilter, runIdFilter);
        String cursor = clean(after);
        int start = 0;
        if (!cursor.isBlank()) {
            for (int i = 0; i < timeline.size(); i++) {
                if (cursor.equals(stringValue(timeline.get(i).get("id")))) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<Map<String, Object>> events = start >= timeline.size() ? List.of() : timeline.subList(start, timeline.size());
        String nextCursor = timeline.isEmpty()
                ? cursor
                : stringValue(timeline.get(timeline.size() - 1).get("id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("events", events);
        out.put("nextCursor", nextCursor);
        return out;
    }

    private static Map<String, Object> runHistory(RicbotApiAppContext appContext, String sessionId, HttpExchange exchange) {
        return new ConsoleRunHistoryService(consoleEventBus(appContext).store()).history(
                sessionId,
                queryParam(exchange, "status"),
                queryParam(exchange, "keyword"),
                parsePositiveInt(queryParam(exchange, "limit"), 50)
        );
    }

    private static Map<String, Object> eventSearch(RicbotApiAppContext appContext, HttpExchange exchange) {
        ConsoleEventSearchQuery query = new ConsoleEventSearchQuery(
                queryParam(exchange, "sessionId"),
                queryParam(exchange, "runId"),
                queryParam(exchange, "category"),
                queryParam(exchange, "status"),
                queryParam(exchange, "keyword"),
                queryParam(exchange, "since"),
                queryParam(exchange, "until"),
                queryParam(exchange, "after"),
                parsePositiveInt(queryParam(exchange, "limit"), 100)
        );
        return new ConsoleEventSearchService(consoleEventBus(appContext).store()).search(query);
    }

    private static Map<String, Object> metricsSummary(RicbotApiAppContext appContext, HttpExchange exchange) {
        ConsoleMetricsQuery query = new ConsoleMetricsQuery(
                queryParam(exchange, "sessionId"),
                queryParam(exchange, "since"),
                queryParam(exchange, "until")
        );
        return new ConsoleMetricsService(consoleEventBus(appContext).store()).summary(query);
    }

    private static void streamSessionEvents(RicbotApiAppContext appContext, String sessionId, HttpExchange exchange) throws IOException {
        sessionManager(appContext).find(sessionId)
                .orElseThrow(() -> new ConsoleNotFoundException("session not found: " + sessionId));
        String cursor = firstNonBlank(queryParam(exchange, "after"), exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        String category = queryParam(exchange, "category");
        boolean once = "true".equalsIgnoreCase(queryParam(exchange, "once"));
        int maxTicks = parsePositiveInt(queryParam(exchange, "maxTicks"), once ? 1 : Integer.MAX_VALUE);

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        int ticks = 0;
        ConsoleEventBus bus = consoleEventBus(appContext);
        LinkedBlockingQueue<ConsoleEvent> queue = new LinkedBlockingQueue<>();
        ConsoleEventBus.Listener listener = event -> {
            if (eventMatchesCategory(event, category)) {
                queue.offer(event);
            }
        };
        try (Writer writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8)) {
            List<Map<String, Object>> replay = bus.listBySession(sessionId, category, cursor, 100).stream()
                    .map(ConsoleEvent::toMap)
                    .toList();
            if (!replay.isEmpty()) {
                cursor = stringValue(replay.get(replay.size() - 1).get("id"));
                writeTimelineBatch(writer, sessionId, replay, cursor);
                writer.flush();
                if (once) {
                    return;
                }
            } else if (once) {
                Map<String, Object> response = sessionEvents(appContext, sessionId, cursor, category);
                List<Map<String, Object>> events = castMapList(response.get("events"));
                String nextCursor = stringValue(response.get("nextCursor"));
                if (!events.isEmpty()) {
                    writeTimelineBatch(writer, sessionId, events, nextCursor);
                } else {
                    writeSseEvent(writer, "heartbeat", "", Map.of(
                            "sessionId", sessionId,
                            "time", Instant.now().toString()
                    ));
                }
                writer.flush();
                return;
            }

            bus.subscribe(sessionId, listener);
            while (!Thread.currentThread().isInterrupted() && ticks < maxTicks) {
                ConsoleEvent event = null;
                try {
                    event = queue.poll(1000L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (event != null) {
                    cursor = event.id();
                    writeTimelineBatch(writer, sessionId, List.of(event.toMap()), cursor);
                } else {
                    writeSseEvent(writer, "heartbeat", "", Map.of(
                            "sessionId", sessionId,
                            "time", Instant.now().toString()
                    ));
                }
                writer.flush();
                ticks++;
                if (ticks >= maxTicks) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Client disconnected; closing the exchange body releases the streaming response resources.
        } finally {
            bus.unsubscribe(sessionId, listener);
        }
    }

    private static Map<String, Object> startSessionRun(RicbotApiAppContext appContext, String sessionId, HttpExchange exchange) throws Exception {
        Map<String, Object> body = readJsonObject(exchange);
        String input = stringValue(body.get("input"));
        if (input.isBlank()) {
            consoleEventRecorder(appContext).recordAction(sessionId, "", "blank_input", "RUN", sessionId,
                    "FAILED", operator(exchange, appContext), "Input cannot be blank",
                    Map.of("sessionId", sessionId));
            return failedRun("blank_input", "Input cannot be blank");
        }
        if (!Boolean.TRUE.equals(runtime(appContext).get("modelConfigured"))) {
            consoleEventRecorder(appContext).recordAction(sessionId, "", "model_not_configured", "RUN", sessionId,
                    "FAILED", operator(exchange, appContext), "Model provider or API key is not configured",
                    Map.of("sessionId", sessionId));
            return failedRun("model_not_configured", "Model provider or API key is not configured");
        }
        if (appContext.getAgentLoop() == null) {
            return failedRun("agent_unavailable", "Agent runtime is not available");
        }

        String runId = "console-run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("consoleRunId", runId);
        metadata.put("mode", firstNonBlank(body.get("mode"), "interactive"));
        metadata.put("workspace", firstNonBlank(body.get("workspace"), appContext.getWorkspace()));
        metadata.put("model", firstNonBlank(body.get("model"), runtime(appContext).get("model")));

        ConsoleRunRecord record = RUN_REGISTRY.create(runId, sessionId, input);
        metadata.put("consoleRunControllerConsumer", (Consumer<AgentRunController>) record::setController);
        Future<?> future = RUN_EXECUTOR.submit(() -> executeConsoleRun(appContext, record, input, metadata));
        record.setFuture(future);
        Map<String, Object> out = record.toMap();
        out.put("message", "Run queued");
        audit(appContext, "run_submit", "RUN", runId, "SUCCESS", operator(exchange, appContext),
                remoteAddress(exchange), exchange.getRequestHeaders().getFirst("User-Agent"),
                "Console run submitted", originWarnings(exchange), UUID.randomUUID().toString());
        audit(appContext, "run_queued", "RUN", runId, "SUCCESS", operator(exchange, appContext),
                remoteAddress(exchange), exchange.getRequestHeaders().getFirst("User-Agent"),
                "Console run queued", List.of(), UUID.randomUUID().toString());
        return out;
    }

    private static void executeConsoleRun(
            RicbotApiAppContext appContext,
            ConsoleRunRecord record,
            String input,
            Map<String, Object> metadata
    ) {
        if (!record.markRunning()) {
            return;
        }
        consoleEventRecorder(appContext).recordRunEvent(record.sessionId(), record.runId(), "run_started", "INFO",
                "Console run started", Map.of("runId", record.runId(), "sessionId", record.sessionId()));
        try {
            appContext.getAgentLoop().processDirect(input, record.sessionId(), "console", "agent-console", metadata, List.of());
            record.markFinished();
            consoleEventRecorder(appContext).recordRunEvent(record.sessionId(), record.runId(), "run_finished", "SUCCESS",
                    "Console run finished", Map.of("runId", record.runId(), "sessionId", record.sessionId()));
        } catch (Exception e) {
            if (record.isCancelled()) {
                return;
            }
            record.markFailed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            consoleEventRecorder(appContext).recordRunEvent(record.sessionId(), record.runId(), "run_failed", "ERROR",
                    "Console run failed", Map.of(
                            "runId", record.runId(),
                            "sessionId", record.sessionId(),
                            "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                    ));
        } finally {
            record.markExecutionComplete();
        }
    }

    private static Map<String, Object> failedRun(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "failed");
        out.put("code", code);
        out.put("message", message);
        return out;
    }

    private static void writeSseEvent(Writer writer, String eventName, String id, Map<String, Object> data) throws IOException {
        writer.write("event: " + eventName + "\n");
        if (id != null && !id.isBlank()) {
            writer.write("id: " + id.replace("\n", "").replace("\r", "") + "\n");
        }
        String json = MAPPER.writeValueAsString(data);
        for (String line : json.split("\\R", -1)) {
            writer.write("data: " + line + "\n");
        }
        writer.write("\n");
    }

    private static void writeTimelineBatch(Writer writer, String sessionId, List<Map<String, Object>> events, String nextCursor) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("events", events != null ? events : List.of());
        data.put("nextCursor", nextCursor != null ? nextCursor : "");
        writeSseEvent(writer, "timeline_batch", nextCursor, data);
    }

    private static int parsePositiveInt(String raw, int fallback) {
        try {
            int value = Integer.parseInt(clean(raw));
            return value > 0 ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static ConsoleEventBus consoleEventBus(RicbotApiAppContext appContext) {
        Path workspace = appContext != null && appContext.getWorkspace() != null
                ? appContext.getWorkspace().toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();
        return EVENT_BUSES.computeIfAbsent(workspace, path -> new ConsoleEventBus(new JsonlConsoleEventStore(path)));
    }

    private static ConsoleEventRecorder consoleEventRecorder(RicbotApiAppContext appContext) {
        return new ConsoleEventRecorder(consoleEventBus(appContext));
    }

    private static boolean eventMatchesCategory(ConsoleEvent event, String category) {
        String safeCategory = clean(category).toLowerCase(Locale.ROOT);
        return event != null
                && (safeCategory.isBlank() || "all".equals(safeCategory) || safeCategory.equals(event.category()));
    }

    private static Map<String, Object> sessionSummary(Map<String, Object> raw) {
        String key = String.valueOf(raw.getOrDefault("key", ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", key);
        out.put("key", key);
        out.put("project", "Ricbot");
        out.put("title", key.isBlank() ? "Session" : key);
        out.put("task", key.isBlank() ? "Backend session" : "Backend session " + key);
        out.put("status", "IDLE");
        out.put("updatedAt", String.valueOf(raw.getOrDefault("updated_at", "")));
        out.put("messageCount", raw.getOrDefault("message_count", 0));
        out.put("toolCount", 0);
        out.put("approvalPendingCount", 0);
        out.put("changedFileCount", 0);
        out.put("riskLevel", "SAFE");
        out.put("summary", "Read-only backend session summary");
        return out;
    }

    private static Map<String, Object> recentChangeSets(RicbotApiAppContext appContext) {
        List<Map<String, Object>> items = new ChangeSetService(appContext.getWorkspace()).list().stream()
                .limit(20)
                .map(GitChangeSet::toMap)
                .toList();
        consoleEventRecorder(appContext).recordAction("", "", "changeset_list_recent", "CHANGESET", "recent",
                "SUCCESS", "console", "Recent ChangeSets listed", Map.of("count", items.size()));
        return Map.of("mode", "backend", "items", items);
    }

    private static Map<String, Object> changeSetDetail(RicbotApiAppContext appContext, String changeSetId) {
        GitChangeSet changeSet = new ChangeSetService(appContext.getWorkspace()).load(changeSetId);
        if (changeSet == null) {
            return Map.of(
                    "id", changeSetId,
                    "found", false,
                    "message", "ChangeSet not found"
            );
        }
        Map<String, Object> out = changeSet.toMap();
        out.put("found", true);
        return out;
    }

    private static Map<String, Object> changeSetDiff(RicbotApiAppContext appContext, String changeSetId) {
        GitChangeSet changeSet = new ChangeSetService(appContext.getWorkspace()).load(changeSetId);
        if (changeSet == null) {
            return Map.of("changeSetId", changeSetId, "diff", "", "message", "暂无真实 diff");
        }
        String diff = truncateDiff(changeSet.diffPatch());
        return Map.of(
                "changeSetId", changeSet.id(),
                "diff", diff,
                "truncated", diff.length() < changeSet.diffPatch().length()
        );
    }

    private static Map<String, Object> changeSetFileDiff(RicbotApiAppContext appContext, String changeSetId, String filePath) {
        GitChangeSet changeSet = new ChangeSetService(appContext.getWorkspace()).load(changeSetId);
        if (changeSet == null) {
            return Map.of("changeSetId", changeSetId, "path", filePath, "diff", "", "message", "暂无真实 diff");
        }
        String diff = filePatch(changeSet.diffPatch(), filePath);
        if (diff.isBlank()) {
            return Map.of("changeSetId", changeSet.id(), "path", filePath, "diff", "", "message", "暂无真实 diff");
        }
        String truncated = truncateDiff(diff);
        return Map.of(
                "changeSetId", changeSet.id(),
                "path", filePath,
                "diff", truncated,
                "truncated", truncated.length() < diff.length()
        );
    }

    private static String truncateDiff(String diff) {
        String safe = diff != null ? diff : "";
        int limit = 80_000;
        return safe.length() <= limit ? safe : safe.substring(0, limit) + "\n... diff truncated ...";
    }

    private static String filePatch(String patch, String path) {
        String safePatch = patch != null ? patch : "";
        String safePath = path != null ? path : "";
        if (safePatch.isBlank() || safePath.isBlank()) {
            return "";
        }
        String marker = "diff --git a/" + safePath + " b/" + safePath;
        int start = safePatch.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int next = safePatch.indexOf("\ndiff --git ", start + 1);
        return next < 0 ? safePatch.substring(start) : safePatch.substring(start, next);
    }

    private static SessionManager sessionManager(RicbotApiAppContext appContext) {
        return appContext.getAgentLoop() != null && appContext.getAgentLoop().getSessions() != null
                ? appContext.getAgentLoop().getSessions()
                : new SessionManager(appContext.getWorkspace());
    }

    private static List<Map<String, Object>> runEventsFromSession(Session session) {
        if (session == null || session.getMetadata() == null) {
            return List.of();
        }
        Object rawTrace = session.getMetadata().get(SessionRuntimeKeys.RUN_TRACE_KEY);
        if (!(rawTrace instanceof Map<?, ?> trace)) {
            return List.of();
        }
        Object rawEvents = trace.get("events");
        if (!(rawEvents instanceof List<?> events)) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object rawEvent : events) {
            if (rawEvent instanceof Map<?, ?> map) {
                out.add(sanitizeMap(map));
            }
        }
        return out;
    }

    private static String stableRunEventId(String sessionId, Map<String, Object> event, int index) {
        String explicit = firstNonBlank(event.get("id"), event.get("eventId"), event.get("event_id"));
        if (!explicit.isBlank()) {
            return safeEventId(sessionId, "run", explicit);
        }
        String runId = firstNonBlank(event.get("run_id"), event.get("runId"));
        String type = stringValue(event.get("type"));
        String at = runEventTime(event, "");
        String basis = (!runId.isBlank() ? runId : "run") + "-" + (!type.isBlank() ? type : "event") + "-" + (!at.isBlank() ? at : index);
        return safeEventId(sessionId, "run", basis);
    }

    private static String runEventTime(Map<String, Object> event, String fallback) {
        return firstNonBlank(event.get("at"), event.get("timestamp"), event.get("createdAt"), event.get("created_at"), fallback);
    }

    private static String runEventTitle(Map<String, Object> event) {
        String type = stringValue(event.get("type"));
        if (!type.isBlank()) {
            return type;
        }
        return "run_event";
    }

    private static String runEventSummary(Map<String, Object> event) {
        String type = stringValue(event.get("type"));
        return switch (type) {
            case "run_start" -> "Agent run started";
            case "run_retry" -> "Retry requested: " + firstNonBlank(event.get("retry_reason"), event.get("retryReason"));
            case "run_stop" -> "Run stopped: " + firstNonBlank(event.get("stop_reason"), event.get("stopReason"));
            case "run_finish" -> "Agent run finished";
            case "run_cancelled" -> "Run cancelled: " + firstNonBlank(event.get("reason"), event.get("stop_reason"), event.get("stopReason"));
            case "run_timeout", "timeout" -> "Run timed out";
            case "capability_warning" -> "Capability warning: " + firstNonBlank(event.get("capability"), event.get("key"));
            case "model_error" -> "Model error: " + firstNonBlank(event.get("error"), event.get("message"));
            case "tool_error", "tool_error_loop" -> "Tool error: " + firstNonBlank(event.get("error"), event.get("detail"), event.get("retry_reason"));
            case "tool_loop" -> "Tool loop detected";
            default -> {
                String summary = firstNonBlank(event.get("summary"), event.get("message"), event.get("detail"));
                yield !summary.isBlank() ? summary : type;
            }
        };
    }

    private static String runEventStatus(Map<String, Object> event) {
        String text = (stringValue(event.get("type")) + " "
                + stringValue(event.get("status")) + " "
                + stringValue(event.get("stop_reason")) + " "
                + stringValue(event.get("error"))).toLowerCase(Locale.ROOT);
        if (text.contains("error") || text.contains("timeout") || text.contains("failed") || text.contains("tool_loop") || text.contains("cancel")) {
            return "ERROR";
        }
        if (text.contains("retry") || text.contains("warning") || text.contains("capability")) {
            return "WARN";
        }
        return "INFO";
    }

    private static String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static List<Map<String, Object>> toolCallsFromMessages(String sessionId, List<Map<String, Object>> messages) {
        Map<String, Map<String, Object>> toolResults = new LinkedHashMap<>();
        for (Map<String, Object> message : messages) {
            if (!"tool".equals(stringValue(message.get("role")))) {
                continue;
            }
            String id = stringValue(message.get("tool_call_id"));
            if (!id.isBlank()) {
                toolResults.put(id, message);
            }
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> message : messages) {
            Object rawToolCalls = message.get("tool_calls");
            if (!(rawToolCalls instanceof List<?> toolCalls)) {
                continue;
            }
            for (Object rawToolCall : toolCalls) {
                if (!(rawToolCall instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> toolCall = sanitizeMap(map);
                String id = stringValue(toolCall.get("id"));
                Map<String, Object> resultMessage = toolResults.getOrDefault(id, Map.of());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", !id.isBlank() ? id : sessionId + "-tool-" + out.size());
                item.put("toolName", toolName(toolCall));
                item.put("status", resultMessage.isEmpty() ? "RUNNING" : "SUCCEEDED");
                item.put("createdAt", stringValue(message.get("timestamp")));
                item.put("durationMs", 0);
                item.put("arguments", toolArguments(toolCall));
                item.put("result", resultMessage.isEmpty() ? Map.of() : sanitizeMap(resultMessage));
                item.put("policy", Map.of(
                        "executionPolicy", "READ_ONLY_DETAIL",
                        "permissionPolicy", "backend persisted session",
                        "approvalRequired", false
                ));
                item.put("refs", Map.of("sessionId", sessionId));
                item.put("raw", toolCall);
                out.add(item);
            }
        }
        return out;
    }

    private static String toolName(Map<String, Object> toolCall) {
        String direct = stringValue(toolCall.get("name"));
        if (!direct.isBlank()) {
            return direct;
        }
        Object function = toolCall.get("function");
        if (function instanceof Map<?, ?> map) {
            String name = stringValue(map.get("name"));
            if (!name.isBlank()) {
                return name;
            }
        }
        return "tool_call";
    }

    private static Object toolArguments(Map<String, Object> toolCall) {
        Object direct = toolCall.get("arguments");
        if (direct instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        Object function = toolCall.get("function");
        if (function instanceof Map<?, ?> map) {
            Object args = map.get("arguments");
            if (args instanceof Map<?, ?> argsMap) {
                return sanitizeMap(argsMap);
            }
            if (args != null) {
                String text = String.valueOf(args);
                try {
                    return sanitizeMap(MAPPER.readValue(text, MAP_TYPE));
                } catch (Exception ignored) {
                    return Map.of("raw", text);
                }
            }
        }
        return direct != null ? direct : Map.of();
    }

    private static List<Map<String, Object>> approvalsForSession(RicbotApiAppContext appContext, String sessionId) {
        if (appContext.getAgentLoop() == null || appContext.getAgentLoop().getApprovalService() == null) {
            return List.of();
        }
        try {
            return approvalApplicationService(appContext).listPending().stream()
                    .map(ConsoleController::approvalMap)
                    .filter(approval -> sessionId.equals(approvalSessionId(approval)))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String approvalSessionId(Map<String, Object> approval) {
        Object pendingToolCall = approval.get("pendingToolCall");
        if (pendingToolCall instanceof Map<?, ?> map) {
            String value = stringValue(map.get("sessionId"));
            if (!value.isBlank()) {
                return value;
            }
        }
        Object direct = approval.get("sessionId");
        return direct != null ? stringValue(direct) : "";
    }

    private static List<Map<String, Object>> changeSetsForSession(RicbotApiAppContext appContext, String sessionId) {
        return new ChangeSetService(appContext.getWorkspace()).list().stream()
                .filter(changeSet -> sessionId.equals(changeSet.sessionId()))
                .limit(20)
                .map(GitChangeSet::toMap)
                .map(ConsoleController::sanitizeMap)
                .toList();
    }

    private static TraceTimeline traceTimelineForSession(RicbotApiAppContext appContext, String sessionId) {
        try {
            return new TraceViewerService(appContext.getWorkspace()).show(sessionId);
        } catch (Exception e) {
            return new TraceTimeline("", sessionId, "", "", "", "UNKNOWN", List.of(),
                    "Trace unavailable.", List.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    Map.of(), Map.of(), Map.of());
        }
    }

    private static String sessionStatus(List<Map<String, Object>> approvals, TraceTimeline traceTimeline) {
        if (!approvals.isEmpty()) {
            return "waiting_approval";
        }
        String traceStatus = traceTimeline != null ? traceTimeline.status() : "";
        if ("FAILED".equalsIgnoreCase(traceStatus)) {
            return "failed";
        }
        if ("COMPLETED".equalsIgnoreCase(traceStatus) || "PASS".equalsIgnoreCase(traceStatus)) {
            return "finished";
        }
        return "idle";
    }

    private static Map<String, Object> timelineEvent(
            String id,
            String type,
            String time,
            String title,
            String summary,
            String status,
            Map<String, Object> payload
    ) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("type", type);
        event.put("time", time);
        event.put("title", title);
        event.put("summary", summary);
        event.put("status", status);
        event.put("payload", payload != null ? payload : Map.of());
        return event;
    }

    private static List<Map<String, Object>> distinctTimeline(List<Map<String, Object>> events) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        java.util.Set<String> semanticKeys = new java.util.LinkedHashSet<>();
        for (Map<String, Object> event : events != null ? events : List.<Map<String, Object>>of()) {
            String id = stringValue(event.get("id"));
            String semantic = timelineSemanticKey(event);
            if (!id.isBlank() && !ids.add(id)) {
                continue;
            }
            if (!semantic.isBlank() && !semanticKeys.add(semantic)) {
                continue;
            }
            out.add(event);
        }
        return out;
    }

    private static String timelineSemanticKey(Map<String, Object> event) {
        String time = stringValue(event.get("time"));
        if (time.isBlank()) {
            return "";
        }
        String semanticType = semanticEventType(event);
        if (semanticType.isBlank()) {
            return "";
        }
        return time + "|" + semanticType;
    }

    private static String semanticEventType(Map<String, Object> event) {
        String title = stringValue(event.get("title"));
        if (isLifecycleEventName(title)) {
            return title;
        }
        String summary = stringValue(event.get("summary"));
        if (isLifecycleEventName(summary)) {
            return summary;
        }
        Object payload = event.get("payload");
        if (payload instanceof Map<?, ?> map) {
            String payloadType = firstNonBlank(map.get("type"), map.get("eventType"), map.get("event_type"));
            if (!payloadType.isBlank()) {
                return payloadType;
            }
        }
        return "";
    }

    private static boolean isLifecycleEventName(String value) {
        return value != null && (value.startsWith("run_")
                || value.endsWith("_error")
                || "timeout".equals(value)
                || "tool_loop".equals(value)
                || "tool_error_loop".equals(value)
                || "capability_warning".equals(value));
    }

    private static String messageTitle(String role) {
        return switch (role) {
            case "assistant" -> "Assistant message";
            case "tool" -> "Tool result";
            case "system" -> "System message";
            default -> "User message";
        };
    }

    private static String contentSummary(Object content) {
        String text = content != null ? String.valueOf(content) : "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > 180 ? text.substring(0, 180) + "..." : text;
    }

    private static String approvalSummary(Map<String, Object> approval) {
        Object risk = approval.get("riskAssessment");
        if (risk instanceof Map<?, ?> map) {
            Object reasons = map.get("reasons");
            if (reasons instanceof List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
            String command = stringValue(map.get("command"));
            if (!command.isBlank()) {
                return command;
            }
        }
        return "Approval request";
    }

    private static String safeEventId(String sessionId, String prefix, Object raw) {
        String value = raw != null ? String.valueOf(raw).replaceAll("[^A-Za-z0-9._:-]", "-") : "";
        if (value.isBlank()) {
            value = UUID.randomUUID().toString();
        }
        return sessionId + "-" + prefix + "-" + value;
    }

    private static List<Map<String, Object>> castMapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(sanitizeMap(map));
            }
        }
        return out;
    }

    private static Map<String, Object> configDoctor(RicbotApiAppContext appContext) {
        ConfigDoctorReport report = new ConfigDoctorService().diagnose(appContext.getConfig(), appContext.getConfigPath());
        return report.toMap();
    }

    private static Map<String, Object> traces(RicbotApiAppContext appContext) {
        TraceTimeline latest = new TraceViewerService(appContext.getWorkspace()).lastTimeline();
        return Map.of(
                "latest", latest.toMap(),
                "items", latest.traceId().isBlank() ? List.of() : List.of(latest.toMap())
        );
    }

    private static Map<String, Object> teamReports(RicbotApiAppContext appContext) {
        TeamEngine engine = new TeamEngine(appContext.getWorkspace());
        List<Map<String, Object>> reports = engine.listSessions().stream()
                .flatMap(session -> session.tasks().stream().map(task -> teamReport(engine, session, task)))
                .sorted(Comparator.comparing(row -> String.valueOf(row.getOrDefault("updatedAt", "")), Comparator.reverseOrder()))
                .limit(20)
                .toList();
        return Map.of("items", reports);
    }

    private static Map<String, Object> teamReport(TeamEngine engine, TeamSession session, TeamTask task) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", session.id());
        row.put("sessionGoal", session.goal());
        row.put("task", task.toMap());
        row.put("updatedAt", task.updatedAt());
        try {
            row.put("report", engine.taskReport(task.id()).toMap());
        } catch (Exception e) {
            row.put("report", Map.of());
            row.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        return row;
    }

    private static Map<String, Object> workspaces(RicbotApiAppContext appContext) {
        List<Map<String, Object>> sessions = new WorkspaceSessionStore(appContext.getWorkspace()).list().stream()
                .map(WorkspaceSession::toMap)
                .limit(50)
                .toList();
        return Map.of("items", sessions);
    }

    private static Map<String, Object> tools(RicbotApiAppContext appContext) {
        if (appContext.getAgentLoop() == null) {
            return Map.of("total", 0, "builtinCount", 0, "mcpCount", 0, "generatedCount", 0, "items", List.of());
        }
        return new ToolRegistryViewerService(
                appContext.getAgentLoop().getTools(),
                appContext.getAgentLoop().getMcpLoader(),
                appContext.getConfig()
        ).tools();
    }

    private static Map<String, Object> mcp(RicbotApiAppContext appContext) {
        if (appContext.getAgentLoop() == null) {
            return Map.of("configuredCount", 0, "connectedCount", 0, "mcpToolCount", 0, "servers", List.of());
        }
        return new ToolRegistryViewerService(
                appContext.getAgentLoop().getTools(),
                appContext.getAgentLoop().getMcpLoader(),
                appContext.getConfig()
        ).mcp();
    }

    private static Map<String, Object> mcpDiagnostics(RicbotApiAppContext appContext) {
        if (appContext.getAgentLoop() == null) {
            return Map.of(
                    "configuredCount", 0,
                    "connectedCount", 0,
                    "mcpToolCount", 0,
                    "schemaHash", "",
                    "warnings", List.of(),
                    "servers", List.of(),
                    "tools", List.of(),
                    "schemaSummary", List.of()
            );
        }
        return new MCPDiagnosticService(
                appContext.getAgentLoop().getTools(),
                appContext.getAgentLoop().getMcpLoader(),
                appContext.getConfig()
        ).diagnostics();
    }

    private static Map<String, Object> createWorkspaceChangeSet(RicbotApiAppContext appContext, String id) {
        WorkspaceSession session = requireActiveManagedWorktree(appContext, id);
        String taskId = String.valueOf(session.metadata().getOrDefault("taskId", "")).trim();
        String teamSessionId = String.valueOf(session.metadata().getOrDefault("teamSessionId", "")).trim();
        try {
            GitChangeSet changeSet = new ChangeSetService(appContext.getWorkspace())
                    .createFromWorkspace(session.id(), Path.of(session.workspacePath()), "console", teamSessionId, taskId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("workspaceId", session.id());
            data.put("taskId", taskId);
            data.put("changeSet", changeSet.toMap());
            data.put("changedFiles", changeSet.changedFiles());
            data.put("changeSetPath", ".changesets/" + changeSet.id() + "/changeset.json");
            return actionResult("workspace.change_create", id, changeSet.status().name(),
                    "workspace changeset created: workspaceId=" + session.id() + ", taskId=" + taskId,
                    data, List.of());
        } catch (IllegalStateException e) {
            String message = e.getMessage() != null ? e.getMessage() : "";
            if (message.contains("no changes") || message.contains("no user changes")) {
                throw new ConsoleConflictException("no user changes found; workspace has no changes to create a ChangeSet: " + session.id());
            }
            throw e;
        }
    }

    private static Map<String, Object> discardWorkspace(RicbotApiAppContext appContext, String id, HttpExchange exchange) throws IOException {
        if (!confirmTrue(exchange)) {
            throw new ConsoleConflictException("discard requires confirm=true");
        }
        WorkspaceSession before = requireActiveManagedWorktree(appContext, id);
        WorkspaceSession discarded = new WorkspaceLifecycleService(appContext.getWorkspace()).discard(before.id(), true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workspaceId", discarded.id());
        data.put("taskId", String.valueOf(discarded.metadata().getOrDefault("taskId", "")));
        data.put("workspace", discarded.toMap());
        return actionResult("workspace.discard", id, discarded.status().name(),
                "workspace discarded: workspaceId=" + discarded.id() + ", taskId=" + data.get("taskId"),
                data, List.of());
    }

    private static WorkspaceSession requireActiveManagedWorktree(RicbotApiAppContext appContext, String rawId) {
        String id = requireSafeId(rawId, "workspace id");
        try {
            WorkspaceSession session = new WorkspaceLifecycleService(appContext.getWorkspace()).resolveManagedWorktree(id);
            if (session.type() != WorkspaceBackendType.GIT_WORKTREE) {
                throw new ConsoleConflictException("workspace is not a managed git worktree: " + id);
            }
            if (session.status() != WorkspaceSessionStatus.ACTIVE) {
                throw new ConsoleConflictException("workspace action requires ACTIVE status: " + session.id() + " status=" + session.status());
            }
            return session;
        } catch (IllegalStateException e) {
            throw new ConsoleConflictException(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ConsoleNotFoundException(e.getMessage());
        }
    }

    private static Map<String, Object> experiences(RicbotApiAppContext appContext) {
        ExperienceStore store = new ExperienceStore(appContext.getWorkspace());
        List<Map<String, Object>> candidates = store.listCandidates().stream()
                .map(ExperienceEntry::toMap)
                .limit(20)
                .toList();
        List<Map<String, Object>> verified = store.listVerified().stream()
                .map(ExperienceEntry::toMap)
                .limit(20)
                .toList();
        return Map.of(
                "stats", statsMap(store.stats()),
                "candidates", candidates,
                "verified", verified
        );
    }

    private static Map<String, Object> statsMap(ExperienceStore.GovernanceStats stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidates", stats.candidates());
        out.put("verified", stats.verified());
        out.put("rejected", stats.rejected());
        out.put("archived", stats.archived());
        out.put("promoted", stats.promoted());
        return out;
    }

    private static Map<String, Object> evalRuns(RicbotApiAppContext appContext) {
        EvalRunsViewerService service = new EvalRunsViewerService(appContext.getWorkspace());
        List<Map<String, Object>> items = service.listRuns(20).stream()
                .map(EvalRunSummary::toMap)
                .toList();
        return Map.of("items", items);
    }

    private static Map<String, Object> evalRunDetail(RicbotApiAppContext appContext, String runId) {
        EvalRunsViewerService service = new EvalRunsViewerService(appContext.getWorkspace());
        EvalRunDetail detail = service.detail(runId);
        return detail.toMap();
    }

    private static Map<String, Object> runSmokeEval(RicbotApiAppContext appContext, HttpExchange exchange) throws IOException {
        List<String> warnings = evalSmokeBodyWarnings(exchange);
        try {
            EvalRunSummary summary = new ConsoleEvalSmokeService(appContext.getWorkspace()).runSmoke();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("runId", summary.getRunId());
            data.put("status", summary.getFailed() > 0 ? "FAILED" : "COMPLETED");
            data.put("summaryPath", ".ricbot/evals/" + summary.getRunId() + "/summary.json");
            data.put("reportPath", ".ricbot/evals/" + summary.getRunId() + "/report.md");
            data.put("artifactDir", summary.getArtifactDir());
            data.put("providerMode", "smoke");
            data.put("model", "smoke-model");
            data.put("summary", summary.toMap());
            return actionResult("eval.smoke", "smoke", String.valueOf(data.get("status")),
                    "eval smoke completed: runId=" + summary.getRunId(),
                    data, warnings);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
        }
    }

    private static Map<String, Object> actions(RicbotApiAppContext appContext) {
        List<Map<String, Object>> items = new ConsoleActionAuditService(appContext.getWorkspace()).recent(30).stream()
                .map(ConsoleActionAuditRecord::toMap)
                .toList();
        return Map.of("items", items);
    }

    private static Map<String, Object> releaseCheck(RicbotApiAppContext appContext) throws IOException {
        Path report = Path.of("target", "release-check-report.md").toAbsolutePath().normalize();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportPath", "target/release-check-report.md");
        out.put("command", "sh scripts/release-check.sh");
        if (!Files.isRegularFile(report)) {
            out.put("status", "empty");
            out.put("exists", false);
            out.put("message", "release-check report not found");
            return out;
        }
        String markdown = redactSensitiveText(Files.readString(report, StandardCharsets.UTF_8));
        out.put("status", "found");
        out.put("exists", true);
        out.put("generatedAt", lineValue(markdown, "- generated_at: "));
        out.put("finalStatus", lineValue(markdown, "- final_status: "));
        out.put("baselineStatus", lineValue(markdown, "- baseline_status: "));
        out.put("evalCompareStatus", tableValue(markdown, "eval compare"));
        out.put("reportMarkdown", markdown.length() > 20_000 ? markdown.substring(0, 20_000) + "\n\n[truncated]" : markdown);
        return out;
    }

    private static String lineValue(String text, String prefix) {
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String tableValue(String text, String step) {
        String needle = "| " + step + " |";
        for (String line : text.split("\\R")) {
            if (line.startsWith(needle)) {
                String[] parts = line.split("\\|");
                return parts.length >= 3 ? parts[2].trim() : "";
            }
        }
        return "";
    }

    private static String redactSensitiveText(String text) {
        String out = text != null ? text : "";
        out = out.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s`|]+", "$1[REDACTED]");
        out = out.replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s`|]+", "$1[REDACTED]");
        out = out.replaceAll("(?i)(secret\\s*[:=]\\s*)[^\\s`|]+", "$1[REDACTED]");
        out = out.replaceAll("(?i)(password\\s*[:=]\\s*)[^\\s`|]+", "$1[REDACTED]");
        out = out.replaceAll("(?i)(authorization\\s*[:=]\\s*)[^\\s`|]+", "$1[REDACTED]");
        out = out.replaceAll("(?i)bearer\\s+[^\\s`|]+", "Bearer [REDACTED]");
        return out;
    }

    private static Map<String, Object> verifyExperience(RicbotApiAppContext appContext, String id) {
        ExperienceStore store = new ExperienceStore(appContext.getWorkspace());
        ExperienceEntry entry = requireExperience(store, id);
        if (entry.status() != ExperienceStatus.CANDIDATE) {
            throw new ConsoleConflictException("only candidate experience can be verified: " + id + " status=" + entry.status());
        }
        ExperienceEntry updated = store.verify(id);
        return actionResult("experience.verify", id, updated.status().name(), "experience verified", updated.toMap(), List.of());
    }

    private static Map<String, Object> rejectExperience(RicbotApiAppContext appContext, String id) {
        ExperienceStore store = new ExperienceStore(appContext.getWorkspace());
        ExperienceEntry entry = requireExperience(store, id);
        if (entry.status() != ExperienceStatus.CANDIDATE) {
            throw new ConsoleConflictException("only candidate experience can be rejected: " + id + " status=" + entry.status());
        }
        ExperienceEntry updated = store.reject(id);
        return actionResult("experience.reject", id, updated.status().name(), "experience rejected", updated.toMap(), List.of());
    }

    private static Map<String, Object> promoteExperienceSkill(RicbotApiAppContext appContext, String id) {
        ExperienceStore store = new ExperienceStore(appContext.getWorkspace());
        ExperienceEntry entry = requireExperience(store, id);
        if (entry.status() != ExperienceStatus.VERIFIED) {
            throw new ConsoleConflictException("only verified experience can be promoted to skill: " + id + " status=" + entry.status());
        }
        ExperienceSkillPromoter.PromotionResult result = new ExperienceSkillPromoter(appContext.getWorkspace(), store).promote(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceExperienceId", result.sourceExperienceId());
        data.put("skillName", result.skillName());
        data.put("skillPath", safeWorkspacePath(appContext, result.skillPath()));
        data.put("created", result.created());
        data.put("alreadyExists", result.alreadyExists());
        String status = result.created() ? "CREATED" : result.alreadyExists() ? "ALREADY_EXISTS" : "OK";
        return actionResult("experience.promoteSkill", id, status, "experience skill promoted", data, List.of());
    }

    private static ExperienceEntry requireExperience(ExperienceStore store, String id) {
        String safeId = requireSafeId(id, "experience id");
        ExperienceEntry entry = store.find(safeId);
        if (entry == null) {
            throw new ConsoleNotFoundException("experience not found: " + safeId);
        }
        return entry;
    }

    private static Map<String, Object> listApprovals(RicbotApiAppContext appContext) {
        ApprovalApplicationService service = approvalApplicationService(appContext);
        List<Map<String, Object>> items = service.listPending().stream()
                .map(ConsoleController::approvalMap)
                .toList();
        consoleEventRecorder(appContext).recordAction("", "", "approval_list_pending", "APPROVAL", "pending",
                "SUCCESS", "console", "Pending approvals listed", Map.of("count", items.size()));
        return Map.of("items", items);
    }

    private static Map<String, Object> approveApproval(RicbotApiAppContext appContext, String id) {
        requireApproval(approvalService(appContext), id);
        ApprovalApplicationService.ApprovalActionResult result = approvalApplicationService(appContext).approveOnly(id);
        Map<String, Object> data = approvalMap(result.request());
        data.put("executed", result.executed());
        data.put("executionType", result.executionType());
        data.put("message", result.message());
        return actionResult("approval.approve", id, result.request().status().name(), result.message(), data, List.of());
    }

    private static Map<String, Object> approveExecuteApproval(RicbotApiAppContext appContext, String id) {
        requireApproval(approvalService(appContext), id);
        ApprovalApplicationService.ApprovalActionResult result = approvalApplicationService(appContext).approveAndExecute(id);
        Map<String, Object> data = approvalMap(result.request());
        data.put("executed", result.executed());
        data.put("executionType", result.executionType());
        data.put("message", result.message());
        return actionResult("approval.approve_execute", id, result.request().status().name(), result.message(), data, List.of());
    }

    private static Map<String, Object> rejectApproval(RicbotApiAppContext appContext, String id) {
        requireApproval(approvalService(appContext), id);
        ApprovalApplicationService.ApprovalActionResult result = approvalApplicationService(appContext).reject(id);
        Map<String, Object> data = approvalMap(result.request());
        data.put("executed", result.executed());
        data.put("executionType", result.executionType());
        data.put("message", result.message());
        return actionResult("approval.reject", id, result.request().status().name(), result.message(), data, List.of());
    }

    private static ApprovalRequest requireApproval(ApprovalService service, String id) {
        String safeId = requireSafeId(id, "approval id");
        ApprovalRequest request = service.find(safeId);
        if (request == null) {
            throw new ConsoleNotFoundException("approval not found: " + safeId);
        }
        return request;
    }

    private static ApprovalService approvalService(RicbotApiAppContext appContext) {
        if (appContext.getAgentLoop() == null || appContext.getAgentLoop().getApprovalService() == null) {
            throw new IllegalStateException("approval service is unavailable");
        }
        return appContext.getAgentLoop().getApprovalService();
    }

    private static ApprovalApplicationService approvalApplicationService(RicbotApiAppContext appContext) {
        return new ApprovalApplicationService(
                approvalService(appContext),
                appContext.getAgentLoop() != null ? appContext.getAgentLoop().getTools() : null,
                appContext.getWorkspace()
        );
    }

    private static Map<String, Object> approvalMap(ApprovalRequest request) {
        return sanitizeMap(request.toMap());
    }

    private static List<String> evalSmokeBodyWarnings(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return List.of();
        }
        String body = new String(bytes, StandardCharsets.UTF_8).trim();
        if (body.isBlank() || "{}".equals(body)) {
            return List.of();
        }
        return List.of("request body ignored; Console smoke eval always uses evals/golden.jsonl, target/eval-console-smoke-workspace, and smoke provider");
    }

    private static boolean confirmTrue(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        Object confirm = body.get("confirm");
        return confirm instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(confirm));
    }

    private static Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(bytes, MAP_TYPE);
            return raw != null ? raw : Map.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON request body");
        }
    }

    private static Map<String, Object> actionResult(
            String action,
            String id,
            String status,
            String message,
            Object data,
            List<String> warnings
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("action", action);
        out.put("id", id);
        out.put("status", status);
        out.put("message", message);
        out.put("data", data != null ? data : Map.of());
        out.put("warnings", warnings != null ? warnings : List.of());
        return out;
    }

    private static String safeWorkspacePath(RicbotApiAppContext appContext, Path path) {
        if (path == null) {
            return "";
        }
        Path workspace = appContext.getWorkspace().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workspace)) {
            return workspace.relativize(normalized).toString();
        }
        return normalized.getFileName() != null ? normalized.getFileName().toString() : "";
    }

    private static String requireSafeId(String raw, String label) {
        String id = raw != null ? raw.trim() : "";
        if (id.isBlank()
                || !SAFE_ID.matcher(id).matches()
                || id.startsWith(".")
                || id.contains("..")
                || id.contains("/")
                || id.contains("\\")) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return id;
    }

    private static String requireSafeSessionId(String raw) {
        String id = raw != null ? raw.trim() : "";
        if (id.isBlank()
                || id.length() > 200
                || id.startsWith(".")
                || id.contains("..")
                || id.contains("/")
                || id.contains("\\")
                || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid session id");
        }
        return id;
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = entry.getKey() != null ? String.valueOf(entry.getKey()) : "";
            if (isSensitiveKey(key)) {
                out.put(key, "[REDACTED]");
            } else {
                out.put(key, sanitizeValue(entry.getValue()));
            }
        }
        return out;
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ConsoleController::sanitizeValue).toList();
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("apikey")
                || normalized.equals("authorization")
                || normalized.contains("secret")
                || normalized.equals("password")
                || normalized.endsWith("password")
                || normalized.equals("token")
                || normalized.endsWith("token")
                || normalized.equals("bearer")
                || normalized.contains("bearer")
                || normalized.equals("cookie")
                || normalized.equals("setcookie");
    }

    private static String version() {
        Package pkg = ConsoleController.class.getPackage();
        String value = pkg != null ? pkg.getImplementationVersion() : null;
        return value != null && !value.isBlank() ? value : "dev";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String stringValue(Object raw) {
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawQuery() : "";
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            String rawKey = idx >= 0 ? part.substring(0, idx) : part;
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            if (name.equals(key)) {
                String rawValue = idx >= 0 ? part.substring(idx + 1) : "";
                return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    @FunctionalInterface
    private interface DataSupplier {
        Map<String, Object> get(RicbotApiAppContext appContext) throws Exception;
    }

    private record ApiHandler(RicbotApiAppContext appContext, DataSupplier supplier) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            try {
                RicbotApiServer.writeJson(exchange, 200, supplier.get(appContext));
            } catch (Exception e) {
                RicbotApiServer.writeJson(exchange, 500, Map.of(
                        "error", Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                "type", "console_error"
                        )
                ));
            }
        }
    }

    private record SessionDetailHandler(RicbotApiAppContext appContext, String prefix) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            boolean get = "GET".equalsIgnoreCase(exchange.getRequestMethod());
            boolean post = "POST".equalsIgnoreCase(exchange.getRequestMethod());
            if (!get && !post) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }

            String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawPath() : "";
            if (!path.startsWith(prefix)) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }
            String rest = path.substring(prefix.length());
            boolean stream = rest.endsWith("/events/stream");
            boolean timeline = rest.endsWith("/timeline");
            boolean events = !stream && rest.endsWith("/events");
            boolean runsHistory = rest.endsWith("/runs/history");
            boolean runs = rest.endsWith("/runs");
            String rawId = stream
                    ? rest.substring(0, rest.length() - "/events/stream".length())
                    : timeline
                    ? rest.substring(0, rest.length() - "/timeline".length())
                    : events
                    ? rest.substring(0, rest.length() - "/events".length())
                    : runsHistory
                    ? rest.substring(0, rest.length() - "/runs/history".length())
                    : runs
                    ? rest.substring(0, rest.length() - "/runs".length())
                    : rest;
            if (rawId.contains("/")) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }

            try {
                String sessionId = requireSafeSessionId(URLDecoder.decode(rawId, StandardCharsets.UTF_8));
                if (post && !runs) {
                    RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                } else if (get && runs) {
                    RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                } else if (runs) {
                    RicbotApiServer.writeJson(exchange, 200, startSessionRun(appContext, sessionId, exchange));
                } else if (runsHistory) {
                    RicbotApiServer.writeJson(exchange, 200, runHistory(appContext, sessionId, exchange));
                } else if (stream) {
                    streamSessionEvents(appContext, sessionId, exchange);
                } else if (timeline) {
                    RicbotApiServer.writeJson(exchange, 200, sessionTimeline(appContext, sessionId, queryParam(exchange, "category"), queryParam(exchange, "runId")));
                } else if (events) {
                    RicbotApiServer.writeJson(exchange, 200, sessionEvents(appContext, sessionId, queryParam(exchange, "after"), queryParam(exchange, "category"), queryParam(exchange, "runId")));
                } else {
                    RicbotApiServer.writeJson(exchange, 200, sessionDetail(appContext, sessionId));
                }
            } catch (ConsoleNotFoundException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, e.getMessage(), "not_found");
            } catch (IllegalArgumentException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, "session 不存在", "not_found");
            } catch (Exception e) {
                RicbotApiServer.writeJson(exchange, 500, Map.of(
                        "error", Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                "type", "console_error"
                        )
                ));
            }
        }
    }

    private record RunStatusHandler(RicbotApiAppContext appContext, String prefix) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            boolean isGet = "GET".equalsIgnoreCase(exchange.getRequestMethod());
            boolean isPost = "POST".equalsIgnoreCase(exchange.getRequestMethod());
            if (!isGet && !isPost) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawPath() : "";
            if (!path.startsWith(prefix)) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }
            boolean cancelRequest = path.endsWith("/cancel");
            if (isPost && !cancelRequest) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (isGet && cancelRequest) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }

            String rawId = cancelRequest
                    ? path.substring(prefix.length(), path.length() - "/cancel".length())
                    : path.substring(prefix.length());
            if (rawId.contains("/")) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }
            try {
                String runId = requireSafeId(URLDecoder.decode(rawId, StandardCharsets.UTF_8), "run id");
                ConsoleRunRecord record = RUN_REGISTRY.find(runId);
                if (record == null) {
                    if (cancelRequest) {
                        RicbotApiServer.writeJson(exchange, 200, failedRun("run_not_found", "Run not found"));
                        return;
                    }
                    RicbotApiServer.writeErrorJson(exchange, 404, "run not found: " + runId, "not_found");
                    return;
                }
                if (cancelRequest) {
                    audit(appContext, "run_cancel_requested", "RUN", runId, "SUCCESS", operator(exchange, appContext),
                            remoteAddress(exchange), exchange.getRequestHeaders().getFirst("User-Agent"),
                            "Run cancellation requested", originWarnings(exchange), UUID.randomUUID().toString());
                    RicbotApiServer.writeJson(exchange, 200, record.cancel(appContext));
                } else {
                    RicbotApiServer.writeJson(exchange, 200, record.toMap());
                }
            } catch (IllegalArgumentException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, "run 不存在", "not_found");
            }
        }
    }

    private record EventSearchHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            RicbotApiServer.writeJson(exchange, 200, eventSearch(appContext, exchange));
        }
    }

    private record MetricsSummaryHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            RicbotApiServer.writeJson(exchange, 200, metricsSummary(appContext, exchange));
        }
    }

    private record ChangeSetHandler(RicbotApiAppContext appContext, String prefix) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawPath() : "";
            if (!path.startsWith(prefix)) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }
            String rest = path.substring(prefix.length());
            try {
                if (rest.endsWith("/diff") && !rest.contains("/files/")) {
                    String id = requireSafeId(URLDecoder.decode(rest.substring(0, rest.length() - "/diff".length()), StandardCharsets.UTF_8), "changeset id");
                    Map<String, Object> body = changeSetDiff(appContext, id);
                    audit(appContext, stringValue(body.get("diff")).isBlank() ? "changeset_diff_missing" : "changeset_diff_view",
                            "CHANGESET", id, stringValue(body.get("diff")).isBlank() ? "EMPTY" : "SUCCESS",
                            operator(exchange, appContext), remoteAddress(exchange), exchange.getRequestHeaders().getFirst("User-Agent"),
                            stringValue(body.getOrDefault("message", "ChangeSet diff viewed")), List.of(), UUID.randomUUID().toString());
                    RicbotApiServer.writeJson(exchange, 200, body);
                    return;
                }
                int filesIndex = rest.indexOf("/files/");
                if (filesIndex > 0 && rest.endsWith("/diff")) {
                    String rawId = rest.substring(0, filesIndex);
                    String rawPath = rest.substring(filesIndex + "/files/".length(), rest.length() - "/diff".length());
                    String id = requireSafeId(URLDecoder.decode(rawId, StandardCharsets.UTF_8), "changeset id");
                    String filePath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
                    Map<String, Object> body = changeSetFileDiff(appContext, id, filePath);
                    audit(appContext, stringValue(body.get("diff")).isBlank() ? "changeset_diff_missing" : "changeset_file_diff_view",
                            "CHANGESET", id, stringValue(body.get("diff")).isBlank() ? "EMPTY" : "SUCCESS",
                            operator(exchange, appContext), remoteAddress(exchange), exchange.getRequestHeaders().getFirst("User-Agent"),
                            "ChangeSet file diff viewed: " + filePath, List.of(), UUID.randomUUID().toString());
                    RicbotApiServer.writeJson(exchange, 200, body);
                    return;
                }
                String id = requireSafeId(URLDecoder.decode(rest, StandardCharsets.UTF_8), "changeset id");
                Map<String, Object> body = changeSetDetail(appContext, id);
                audit(appContext, "changeset_view", "CHANGESET", id, Boolean.TRUE.equals(body.get("found")) ? "SUCCESS" : "NOT_FOUND",
                        operator(exchange, appContext), remoteAddress(exchange), exchange.getRequestHeaders().getFirst("User-Agent"),
                        stringValue(body.getOrDefault("message", "ChangeSet viewed")), List.of(), UUID.randomUUID().toString());
                RicbotApiServer.writeJson(exchange, 200, body);
            } catch (IllegalArgumentException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, e.getMessage(), "not_found");
            }
        }
    }

    private record WorkspaceTreeHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            try {
                int depth = parsePositiveInt(queryParam(exchange, "depth"), 3);
                boolean includeHidden = "true".equalsIgnoreCase(queryParam(exchange, "includeHidden"));
                WorkspaceTreeResponse body = new ConsoleWorkspaceService(appContext.getWorkspace())
                        .tree(queryParam(exchange, "root"), depth, includeHidden);
                RicbotApiServer.writeJson(exchange, 200, body);
            } catch (ConsoleWorkspaceException e) {
                RicbotApiServer.writeErrorJson(exchange, e.status(), e.getMessage(), e.code());
            }
        }
    }

    private record WorkspaceFileContentHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            try {
                WorkspaceFileContent body = new ConsoleWorkspaceService(appContext.getWorkspace())
                        .fileContent(queryParam(exchange, "path"));
                RicbotApiServer.writeJson(exchange, 200, body);
            } catch (ConsoleWorkspaceException e) {
                RicbotApiServer.writeErrorJson(exchange, e.status(), e.getMessage(), e.code());
            }
        }
    }

    private record WorkspaceSearchHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            try {
                int limit = parsePositiveInt(queryParam(exchange, "limit"), 50);
                boolean includeHidden = "true".equalsIgnoreCase(queryParam(exchange, "includeHidden"));
                WorkspaceSearchResponse body = new ConsoleWorkspaceService(appContext.getWorkspace())
                        .search(queryParam(exchange, "keyword"), limit, includeHidden);
                RicbotApiServer.writeJson(exchange, 200, body);
            } catch (ConsoleWorkspaceException e) {
                RicbotApiServer.writeErrorJson(exchange, e.status(), e.getMessage(), e.code());
            }
        }
    }

    private record ExperienceActionHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            String[] parts = actionParts(exchange, "/console/api/experiences/");
            if (parts.length != 2) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }
            String id = parts[0];
            String action = switch (parts[1]) {
                case "verify" -> "experience.verify";
                case "reject" -> "experience.reject";
                case "promote-skill" -> "experience.promoteSkill";
                default -> "";
            };
            executeConsolePostAction(exchange, appContext, action, "EXPERIENCE", id, () -> switch (parts[1]) {
                case "verify" -> verifyExperience(appContext, requireSafeId(id, "experience id"));
                case "reject" -> rejectExperience(appContext, requireSafeId(id, "experience id"));
                case "promote-skill" -> promoteExperienceSkill(appContext, requireSafeId(id, "experience id"));
                default -> throw new ConsoleNotFoundException("资源不存在");
            });
        }
    }

    private record WorkspaceActionHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            String[] parts = actionParts(exchange, "/console/api/workspaces/");
            if (parts.length != 2) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }
            String id = parts[0];
            String action = switch (parts[1]) {
                case "change-create" -> "workspace.change_create";
                case "discard" -> "workspace.discard";
                default -> "";
            };
            executeConsolePostAction(exchange, appContext, action, "WORKSPACE", id, () -> switch (parts[1]) {
                case "change-create" -> createWorkspaceChangeSet(appContext, requireSafeId(id, "workspace id"));
                case "discard" -> discardWorkspace(appContext, requireSafeId(id, "workspace id"), exchange);
                default -> throw new ConsoleNotFoundException("资源不存在");
            });
        }
    }

    private record ApprovalHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            try {
                String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawPath() : "";
                if ("GET".equalsIgnoreCase(method)
                        && ("/console/api/approvals".equals(path) || "/console/api/approvals/".equals(path)
                        || "/api/console/approvals".equals(path) || "/api/console/approvals/".equals(path))) {
                    if (!appContext.isAuthorized(exchange)) {
                        RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                        return;
                    }
                    RicbotApiServer.writeJson(exchange, 200, listApprovals(appContext));
                    return;
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                    return;
                }
                String prefix = path.startsWith("/api/console/approvals/")
                        ? "/api/console/approvals/"
                        : "/console/api/approvals/";
                String[] parts = actionParts(exchange, prefix);
                if (parts.length != 2) {
                    RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                    return;
                }
                String id = parts[0];
                String action = switch (parts[1]) {
                    case "approve" -> "approval.approve";
                    case "approve-only" -> "approval_approve_only";
                    case "approve-execute" -> "approval_approve_execute";
                    case "reject" -> "approval.reject";
                    default -> "";
                };
                executeConsolePostAction(exchange, appContext, action, "APPROVAL", id, () -> switch (parts[1]) {
                    case "approve", "approve-only" -> approveApproval(appContext, requireSafeId(id, "approval id"));
                    case "approve-execute" -> approveExecuteApproval(appContext, requireSafeId(id, "approval id"));
                    case "reject" -> rejectApproval(appContext, requireSafeId(id, "approval id"));
                    default -> throw new ConsoleNotFoundException("资源不存在");
                });
            } catch (ConsoleNotFoundException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, e.getMessage(), "not_found");
            } catch (ConsoleConflictException e) {
                RicbotApiServer.writeErrorJson(exchange, 409, e.getMessage(), "conflict");
            } catch (IllegalStateException e) {
                RicbotApiServer.writeErrorJson(exchange, 409, e.getMessage(), "conflict");
            } catch (IllegalArgumentException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, e.getMessage(), "not_found");
            } catch (Exception e) {
                RicbotApiServer.writeJson(exchange, 500, Map.of(
                        "error", Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                "type", "console_error"
                        )
                ));
            }
        }
    }

    private static String[] actionParts(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawPath() : "";
        if (!path.startsWith(prefix)) {
            return new String[0];
        }
        String rest = path.substring(prefix.length());
        if (rest.isBlank() || rest.contains("%2F") || rest.contains("%2f")) {
            return new String[0];
        }
        return rest.split("/", -1);
    }

    @FunctionalInterface
    private interface ConsoleActionSupplier {
        Map<String, Object> execute() throws Exception;
    }

    private static void executeConsolePostAction(
            HttpExchange exchange,
            RicbotApiAppContext appContext,
            String action,
            String targetType,
            String targetId,
            ConsoleActionSupplier supplier
    ) throws IOException {
        String requestId = UUID.randomUUID().toString();
        String safeAction = action != null && !action.isBlank() ? action : "console.unknown";
        String safeTargetId = targetId != null ? targetId : "";
        String remoteAddress = remoteAddress(exchange);
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        List<String> warnings = originWarnings(exchange);

        if (!appContext.isAuthorized(exchange)) {
            audit(appContext, safeAction, targetType, safeTargetId, "UNAUTHORIZED", operator(exchange, appContext),
                    remoteAddress, userAgent, "unauthorized console action", warnings, requestId);
            RicbotApiServer.writeJson(exchange, 401, errorBody("缺少或无效的 Bearer token", "authentication_error", warnings));
            return;
        }
        String originError = originError(exchange, appContext);
        if (originError != null) {
            audit(appContext, safeAction, targetType, safeTargetId, "UNAUTHORIZED", operator(exchange, appContext),
                    remoteAddress, userAgent, originError, warnings, requestId);
            RicbotApiServer.writeJson(exchange, 403, errorBody(originError, "forbidden", warnings));
            return;
        }
        if (!RATE_LIMITER.allow(remoteAddress, safeAction)) {
            audit(appContext, safeAction, targetType, safeTargetId, "FAILED", operator(exchange, appContext),
                    remoteAddress, userAgent, "console action rate limit exceeded", warnings, requestId);
            RicbotApiServer.writeJson(exchange, 429, errorBody("Console action rate limit exceeded", "rate_limit_exceeded", warnings));
            return;
        }

        try {
            Map<String, Object> result = supplier.execute();
            List<String> auditWarnings = audit(appContext, safeAction, targetType, safeTargetId, "SUCCESS", operator(exchange, appContext),
                    remoteAddress, userAgent, String.valueOf(result.getOrDefault("message", "console action succeeded")), warnings, requestId);
            RicbotApiServer.writeJson(exchange, 200, withWarnings(result, combineWarnings(warnings, auditWarnings)));
        } catch (ConsoleNotFoundException e) {
            List<String> auditWarnings = audit(appContext, safeAction, targetType, safeTargetId, "NOT_FOUND", operator(exchange, appContext),
                    remoteAddress, userAgent, e.getMessage(), warnings, requestId);
            RicbotApiServer.writeJson(exchange, 404, errorBody(e.getMessage(), "not_found", combineWarnings(warnings, auditWarnings)));
        } catch (ConsoleConflictException e) {
            List<String> auditWarnings = audit(appContext, safeAction, targetType, safeTargetId, "CONFLICT", operator(exchange, appContext),
                    remoteAddress, userAgent, e.getMessage(), warnings, requestId);
            RicbotApiServer.writeJson(exchange, 409, errorBody(e.getMessage(), "conflict", combineWarnings(warnings, auditWarnings)));
        } catch (IllegalStateException e) {
            List<String> auditWarnings = audit(appContext, safeAction, targetType, safeTargetId, "CONFLICT", operator(exchange, appContext),
                    remoteAddress, userAgent, e.getMessage(), warnings, requestId);
            RicbotApiServer.writeJson(exchange, 409, errorBody(e.getMessage(), "conflict", combineWarnings(warnings, auditWarnings)));
        } catch (IllegalArgumentException e) {
            List<String> auditWarnings = audit(appContext, safeAction, targetType, safeTargetId, "NOT_FOUND", operator(exchange, appContext),
                    remoteAddress, userAgent, e.getMessage(), warnings, requestId);
            RicbotApiServer.writeJson(exchange, 404, errorBody(e.getMessage(), "not_found", combineWarnings(warnings, auditWarnings)));
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            List<String> auditWarnings = audit(appContext, safeAction, targetType, safeTargetId, "FAILED", operator(exchange, appContext),
                    remoteAddress, userAgent, message, warnings, requestId);
            RicbotApiServer.writeJson(exchange, 500, errorBody(message, "console_error", combineWarnings(warnings, auditWarnings)));
        }
    }

    private static List<String> combineWarnings(List<String> first, List<String> second) {
        List<String> out = new java.util.ArrayList<>();
        if (first != null) {
            out.addAll(first);
        }
        if (second != null) {
            out.addAll(second);
        }
        return out.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private static Map<String, Object> withWarnings(Map<String, Object> result, List<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<>(result != null ? result : Map.of());
        List<String> existing = out.get("warnings") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        List<String> merged = new java.util.ArrayList<>(existing);
        if (warnings != null) {
            merged.addAll(warnings);
        }
        out.put("warnings", merged.stream().filter(value -> value != null && !value.isBlank()).distinct().toList());
        return out;
    }

    private static Map<String, Object> errorBody(String message, String type, List<String> warnings) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message != null ? message : "");
        error.put("type", type);
        error.put("warnings", warnings != null ? warnings : List.of());
        return Map.of("error", error);
    }

    private static List<String> audit(
            RicbotApiAppContext appContext,
            String action,
            String targetType,
            String targetId,
            String result,
            String operator,
            String remoteAddress,
            String userAgent,
            String message,
            List<String> warnings,
            String requestId
    ) {
        List<String> safeWarnings = warnings != null ? warnings : List.of();
        ConsoleActionAuditRecord record = new ConsoleActionAuditRecord(
                null,
                null,
                action,
                targetType,
                targetId,
                result,
                operator,
                remoteAddress,
                userAgent,
                message,
                safeWarnings,
                requestId
        );
        List<String> auditWarnings = new ConsoleActionAuditService(appContext.getWorkspace()).append(record);
        try {
            String runId = "";
            String sessionId = "";
            Map<String, Object> payload = new LinkedHashMap<>(record.toMap());
            if ("RUN".equalsIgnoreCase(targetType)) {
                ConsoleRunRecord run = RUN_REGISTRY.find(targetId);
                if (run != null) {
                    runId = run.runId();
                    sessionId = run.sessionId();
                    payload.put("run", run.toMap());
                    payload.put("inputPreview", run.inputPreview());
                }
            }
            payload.put("auditWarnings", auditWarnings);
            consoleEventRecorder(appContext).recordAction(
                    sessionId,
                    runId,
                    action,
                    targetType,
                    targetId,
                    result,
                    operator,
                    message,
                    payload
            );
        } catch (Exception ignored) {
            // Console event recording is best-effort and must not affect the action response.
        }
        return auditWarnings;
    }

    private static List<String> originWarnings(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        if ((origin == null || origin.isBlank()) && (referer == null || referer.isBlank())) {
            return List.of("Origin/Referer header missing; allowed for CLI/curl console action");
        }
        return List.of();
    }

    private static String originError(HttpExchange exchange, RicbotApiAppContext appContext) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank() && !isAllowedConsoleOrigin(origin, appContext, exchange)) {
            return "invalid Console action Origin";
        }
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        if ((origin == null || origin.isBlank()) && referer != null && !referer.isBlank()
                && !isAllowedConsoleOrigin(referer, appContext, exchange)) {
            return "invalid Console action Referer";
        }
        return null;
    }

    private static boolean isAllowedConsoleOrigin(String raw, RicbotApiAppContext appContext, HttpExchange exchange) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || !RicbotApiAppContext.isLoopbackHost(host)) {
                return false;
            }
            int port = uri.getPort();
            int localPort = exchange.getLocalAddress() != null ? exchange.getLocalAddress().getPort() : -1;
            return port < 0 || localPort <= 0 || port == localPort;
        } catch (Exception e) {
            return false;
        }
    }

    private static String operator(HttpExchange exchange, RicbotApiAppContext appContext) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "api-token";
        }
        return appContext.isConsoleExposedBeyondLoopback() ? "anonymous" : "console";
    }

    private static String remoteAddress(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : remote != null ? remote.getHostString() : "";
    }

    private record EvalRunsHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawPath() : "";
            if ("POST".equalsIgnoreCase(method) && "/console/api/evals/smoke".equals(path)) {
                executeConsolePostAction(exchange, appContext, "eval.smoke", "EVAL", "smoke",
                        () -> runSmokeEval(appContext, exchange));
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            try {
                if ("/console/api/evals".equals(path) || "/console/api/evals/".equals(path)) {
                    RicbotApiServer.writeJson(exchange, 200, evalRuns(appContext));
                    return;
                }
                String prefix = "/console/api/evals/";
                if (path.startsWith(prefix)) {
                    String runId = path.substring(prefix.length());
                    if (runId.isBlank() || runId.contains("/")) {
                        RicbotApiServer.writeErrorJson(exchange, 404, "eval run 不存在", "not_found");
                        return;
                    }
                    RicbotApiServer.writeJson(exchange, 200, evalRunDetail(appContext, runId));
                    return;
                }
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
            } catch (EvalRunsViewerService.EvalRunNotFoundException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, e.getMessage(), "not_found");
            } catch (IllegalArgumentException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, "eval run 不存在", "not_found");
            } catch (Exception e) {
                RicbotApiServer.writeJson(exchange, 500, Map.of(
                        "error", Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                "type", "console_error"
                        )
                ));
            }
        }
    }

    private record ConsolePageHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "";
            if (!"/console".equals(path) && !"/console/".equals(path)) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }
            byte[] bytes = ConsolePage.html().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static final class ConsoleConflictException extends RuntimeException {
        private ConsoleConflictException(String message) {
            super(message);
        }
    }

    private static final class ConsoleNotFoundException extends RuntimeException {
        private ConsoleNotFoundException(String message) {
            super(message);
        }
    }

    private static void recordRunCancelledEvent(RicbotApiAppContext appContext, ConsoleRunRecord record, String previousStatus) {
        if (appContext == null || record == null || "cancelled".equals(previousStatus)) {
            return;
        }
        try {
            SessionManager manager = sessionManager(appContext);
            Session session = manager.getOrCreate(record.sessionId);
            synchronized (session) {
                Map<String, Object> trace = new LinkedHashMap<>();
                Object rawTrace = session.getMetadata().get(SessionRuntimeKeys.RUN_TRACE_KEY);
                if (rawTrace instanceof Map<?, ?> map) {
                    trace.putAll(sanitizeMap(map));
                }
                List<Map<String, Object>> events = new java.util.ArrayList<>(castMapList(trace.get("events")));
                String requestedAt = Instant.now().toString();
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("id", record.runId + "-run-cancelled");
                event.put("type", "run_cancelled");
                event.put("runId", record.runId);
                event.put("run_id", record.runId);
                event.put("sessionId", record.sessionId);
                event.put("session_id", record.sessionId);
                event.put("reason", "console run cancelled");
                event.put("requestedAt", requestedAt);
                event.put("createdAt", requestedAt);
                event.put("previousStatus", previousStatus != null ? previousStatus : "");
                event.put("currentStatus", "cancelled");
                events.add(event);
                trace.put("events", events);
                trace.put("stop_reason", "cancelled");
                session.getMetadata().put(SessionRuntimeKeys.RUN_TRACE_KEY, trace);
                session.setUpdatedAt(Instant.now());
                manager.save(session);
            }
            consoleEventRecorder(appContext).recordRunEvent(record.sessionId, record.runId, "run_cancelled", "CANCELLED",
                    "Console run cancelled", Map.of(
                            "runId", record.runId,
                            "sessionId", record.sessionId,
                            "reason", "console run cancelled",
                            "previousStatus", previousStatus != null ? previousStatus : "",
                            "currentStatus", "cancelled"
                    ));
        } catch (Exception ignored) {
            // Cancel state is authoritative in the registry; timeline persistence is best-effort.
        }
    }

    private static final class ConsoleRunRegistry {
        private final Map<String, ConsoleRunRecord> runs = new ConcurrentHashMap<>();

        private ConsoleRunRecord create(String runId, String sessionId, String input) {
            ConsoleRunRecord record = new ConsoleRunRecord(runId, sessionId, input);
            runs.put(runId, record);
            return record;
        }

        private ConsoleRunRecord find(String runId) {
            return runs.get(runId);
        }
    }

    private static final class ConsoleRunRecord {
        private final String runId;
        private final String sessionId;
        private final String inputPreview;
        private final String createdAt;
        private volatile String status = "queued";
        private volatile String startedAt = "";
        private volatile String finishedAt = "";
        private volatile String error = "";
        private volatile Future<?> future;
        private volatile AgentRunController controller;
        private final CountDownLatch executionDone = new CountDownLatch(1);
        private boolean executionStarted = false;

        private ConsoleRunRecord(String runId, String sessionId, String input) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.inputPreview = contentSummary(input);
            this.createdAt = Instant.now().toString();
        }

        private String sessionId() {
            return sessionId;
        }

        private String runId() {
            return runId;
        }

        private String inputPreview() {
            return inputPreview;
        }

        private synchronized void setFuture(Future<?> future) {
            this.future = future;
            if ("cancelled".equals(status)) {
                future.cancel(true);
            }
        }

        private synchronized void setController(AgentRunController controller) {
            this.controller = controller;
            if ("cancelled".equals(status) && controller != null) {
                controller.cancel("console run cancelled");
            }
        }

        private synchronized boolean markRunning() {
            if (!"queued".equals(status)) {
                return false;
            }
            executionStarted = true;
            status = "running";
            startedAt = Instant.now().toString();
            return true;
        }

        private synchronized void markFinished() {
            if (isTerminal()) {
                return;
            }
            status = "finished";
            finishedAt = Instant.now().toString();
        }

        private synchronized void markFailed(String message) {
            if (isTerminal()) {
                return;
            }
            status = "failed";
            error = message != null ? message : "";
            finishedAt = Instant.now().toString();
        }

        private synchronized boolean isCancelled() {
            return "cancelled".equals(status);
        }

        private Map<String, Object> cancel(RicbotApiAppContext appContext) {
            Map<String, Object> out;
            Future<?> futureToCancel;
            AgentRunController controllerToCancel;
            boolean waitForExecution;
            String previousStatus;
            synchronized (this) {
                if ("finished".equals(status) || "failed".equals(status)) {
                    out = toMap();
                    out.put("message", "Run already finished");
                    return out;
                }
                previousStatus = status;
                status = "cancelled";
                if (finishedAt.isBlank()) {
                    finishedAt = Instant.now().toString();
                }
                futureToCancel = future;
                controllerToCancel = controller;
                waitForExecution = executionStarted;
                out = toMap();
            }
            if (controllerToCancel != null) {
                controllerToCancel.cancel("console run cancelled");
            }
            recordRunCancelledEvent(appContext, this, previousStatus);
            if (futureToCancel != null) {
                futureToCancel.cancel(true);
            }
            if (!waitForExecution) {
                executionDone.countDown();
            } else {
                awaitExecutionDone();
            }
            out.put("message", "Run cancellation requested");
            AgentRunController finalController = controller;
            out.put("controllerCancelled", finalController != null && finalController.cancelled());
            return out;
        }

        private void awaitExecutionDone() {
            try {
                executionDone.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void markExecutionComplete() {
            executionDone.countDown();
        }

        private boolean isTerminal() {
            return "finished".equals(status) || "failed".equals(status) || "cancelled".equals(status);
        }

        private synchronized Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("sessionId", sessionId);
            out.put("status", status);
            out.put("createdAt", createdAt);
            out.put("startedAt", startedAt);
            out.put("finishedAt", finishedAt);
            out.put("error", error.isBlank() ? null : error);
            out.put("inputPreview", inputPreview);
            out.put("controllerAttached", controller != null);
            return out;
        }
    }

    private static final class ConsolePostRateLimiter {
        private final int maxRequests;
        private final long windowMillis;
        private final LongSupplier clock;
        private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

        private ConsolePostRateLimiter(int maxRequests, long windowMillis, LongSupplier clock) {
            this.maxRequests = maxRequests;
            this.windowMillis = windowMillis;
            this.clock = clock;
        }

        private boolean allow(String remoteAddress, String action) {
            long now = clock.getAsLong();
            String key = (remoteAddress != null ? remoteAddress : "") + "|" + (action != null ? action : "");
            Deque<Long> deque = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            synchronized (deque) {
                while (!deque.isEmpty() && now - deque.peekFirst() > windowMillis) {
                    deque.removeFirst();
                }
                if (deque.size() >= maxRequests) {
                    return false;
                }
                deque.addLast(now);
                return true;
            }
        }
    }
}
