package ricbot.integration.api.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ricbot.domain.config.ConfigDoctorReport;
import ricbot.domain.config.ConfigDoctorService;
import ricbot.domain.eval.EvalRunDetail;
import ricbot.domain.eval.EvalRunSummary;
import ricbot.domain.eval.EvalRunsViewerService;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceSkillPromoter;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.experience.ExperienceStatus;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ConsoleController {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private ConsoleController() {
    }

    public static void register(HttpServer server, RicbotApiAppContext appContext) {
        server.createContext("/console/api/health", healthHandler(appContext));
        server.createContext("/console/api/config-doctor", configDoctorHandler(appContext));
        server.createContext("/console/api/traces", tracesHandler(appContext));
        server.createContext("/console/api/team-reports", teamReportsHandler(appContext));
        server.createContext("/console/api/workspaces", workspacesHandler(appContext));
        server.createContext("/console/api/experiences/", experienceActionsHandler(appContext));
        server.createContext("/console/api/experiences", experiencesHandler(appContext));
        server.createContext("/console/api/approvals", approvalsHandler(appContext));
        server.createContext("/console/api/evals", evalsHandler(appContext));
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

    public static HttpHandler experienceActionsHandler(RicbotApiAppContext appContext) {
        return new ExperienceActionHandler(appContext);
    }

    public static HttpHandler approvalsHandler(RicbotApiAppContext appContext) {
        return new ApprovalHandler(appContext);
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
        ApprovalService service = approvalService(appContext);
        List<Map<String, Object>> items = service.listPending().stream()
                .map(ConsoleController::approvalMap)
                .toList();
        return Map.of("items", items);
    }

    private static Map<String, Object> approveApproval(RicbotApiAppContext appContext, String id) {
        ApprovalService service = approvalService(appContext);
        ApprovalRequest existing = requireApproval(service, id);
        if (existing.status() != ApprovalRequest.ApprovalStatus.PENDING) {
            throw new ConsoleConflictException("approval request already handled: " + id + " status=" + existing.status());
        }
        ApprovalRequest updated = service.approve(id);
        return actionResult("approval.approve", id, updated.status().name(), "approval approved", approvalMap(updated), List.of());
    }

    private static Map<String, Object> rejectApproval(RicbotApiAppContext appContext, String id) {
        ApprovalService service = approvalService(appContext);
        ApprovalRequest existing = requireApproval(service, id);
        if (existing.status() != ApprovalRequest.ApprovalStatus.PENDING) {
            throw new ConsoleConflictException("approval request already handled: " + id + " status=" + existing.status());
        }
        ApprovalRequest updated = service.reject(id);
        return actionResult("approval.reject", id, updated.status().name(), "approval rejected", approvalMap(updated), List.of());
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

    private static Map<String, Object> approvalMap(ApprovalRequest request) {
        return sanitizeMap(request.toMap());
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
                || normalized.endsWith("token");
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

    private record ExperienceActionHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            try {
                String[] parts = actionParts(exchange, "/console/api/experiences/");
                if (parts.length != 2) {
                    RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                    return;
                }
                String id = requireSafeId(parts[0], "experience id");
                Map<String, Object> result = switch (parts[1]) {
                    case "verify" -> verifyExperience(appContext, id);
                    case "reject" -> rejectExperience(appContext, id);
                    case "promote-skill" -> promoteExperienceSkill(appContext, id);
                    default -> null;
                };
                if (result == null) {
                    RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                    return;
                }
                RicbotApiServer.writeJson(exchange, 200, result);
            } catch (ConsoleNotFoundException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, e.getMessage(), "not_found");
            } catch (ConsoleConflictException e) {
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

    private record ApprovalHandler(RicbotApiAppContext appContext) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!appContext.isAuthorized(exchange)) {
                RicbotApiServer.writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }
            String method = exchange.getRequestMethod();
            try {
                String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawPath() : "";
                if ("GET".equalsIgnoreCase(method)
                        && ("/console/api/approvals".equals(path) || "/console/api/approvals/".equals(path))) {
                    RicbotApiServer.writeJson(exchange, 200, listApprovals(appContext));
                    return;
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                    return;
                }
                String[] parts = actionParts(exchange, "/console/api/approvals/");
                if (parts.length != 2) {
                    RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                    return;
                }
                String id = requireSafeId(parts[0], "approval id");
                Map<String, Object> result = switch (parts[1]) {
                    case "approve" -> approveApproval(appContext, id);
                    case "reject" -> rejectApproval(appContext, id);
                    default -> null;
                };
                if (result == null) {
                    RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                    return;
                }
                RicbotApiServer.writeJson(exchange, 200, result);
            } catch (ConsoleNotFoundException e) {
                RicbotApiServer.writeErrorJson(exchange, 404, e.getMessage(), "not_found");
            } catch (ConsoleConflictException e) {
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

    private record EvalRunsHandler(RicbotApiAppContext appContext) implements HttpHandler {
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
}
