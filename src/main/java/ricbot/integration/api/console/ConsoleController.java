package ricbot.integration.api.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;
import ricbot.domain.trace.TraceTimeline;
import ricbot.domain.trace.TraceViewerService;
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
import java.net.InetSocketAddress;
import java.net.URI;
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
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class ConsoleController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final ConsolePostRateLimiter RATE_LIMITER = new ConsolePostRateLimiter(20, 10_000L, System::currentTimeMillis);

    private ConsoleController() {
    }

    public static void register(HttpServer server, RicbotApiAppContext appContext) {
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
            if (message.contains("no changes")) {
                throw new ConsoleConflictException("workspace has no changes to create a ChangeSet: " + session.id());
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
                        && ("/console/api/approvals".equals(path) || "/console/api/approvals/".equals(path))) {
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
                String[] parts = actionParts(exchange, "/console/api/approvals/");
                if (parts.length != 2) {
                    RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                    return;
                }
                String id = parts[0];
                String action = switch (parts[1]) {
                    case "approve" -> "approval.approve";
                    case "reject" -> "approval.reject";
                    default -> "";
                };
                executeConsolePostAction(exchange, appContext, action, "APPROVAL", id, () -> switch (parts[1]) {
                    case "approve" -> approveApproval(appContext, requireSafeId(id, "approval id"));
                    case "reject" -> rejectApproval(appContext, requireSafeId(id, "approval id"));
                    default -> throw new ConsoleNotFoundException("资源不存在");
                });
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
        return new ConsoleActionAuditService(appContext.getWorkspace()).append(record);
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
