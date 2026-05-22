package ricbot.integration.api.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ricbot.domain.config.ConfigDoctorReport;
import ricbot.domain.config.ConfigDoctorService;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;
import ricbot.domain.trace.TraceTimeline;
import ricbot.domain.trace.TraceViewerService;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.integration.api.RicbotApiAppContext;
import ricbot.integration.api.RicbotApiServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConsoleController {
    private ConsoleController() {
    }

    public static void register(HttpServer server, RicbotApiAppContext appContext) {
        server.createContext("/console/api/health", healthHandler(appContext));
        server.createContext("/console/api/config-doctor", configDoctorHandler(appContext));
        server.createContext("/console/api/traces", tracesHandler(appContext));
        server.createContext("/console/api/team-reports", teamReportsHandler(appContext));
        server.createContext("/console/api/workspaces", workspacesHandler(appContext));
        server.createContext("/console/api/experiences", experiencesHandler(appContext));
        server.createContext("/console", pageHandler(appContext));
    }

    public static HttpHandler pageHandler(RicbotApiAppContext appContext) {
        return new ConsolePageHandler(appContext);
    }

    public static HttpHandler healthHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::health);
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

    public static HttpHandler workspacesHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::workspaces);
    }

    public static HttpHandler experiencesHandler(RicbotApiAppContext appContext) {
        return new ApiHandler(appContext, ConsoleController::experiences);
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
}
