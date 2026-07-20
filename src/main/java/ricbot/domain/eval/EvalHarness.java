package ricbot.domain.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;
import ricbot.infra.config.Config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class EvalHarness {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentLoop agentLoop;
    private final Config config;
    private final EvalRecordingProvider recordingProvider;

    public EvalHarness(AgentLoop agentLoop, Config config) {
        this(agentLoop, config, null);
    }

    public EvalHarness(AgentLoop agentLoop, Config config, EvalRecordingProvider recordingProvider) {
        this.agentLoop = agentLoop;
        this.config = config;
        this.recordingProvider = recordingProvider;
    }

    public EvalRunSummary run(EvalOptions options) throws Exception {
        if (options == null || options.getScenariosPath() == null) {
            throw new IllegalArgumentException("scenarios path is required");
        }
        Path scenariosPath = options.getScenariosPath().toAbsolutePath().normalize();
        List<EvalScenario> scenarios = applyScenarioFilters(loadScenarios(scenariosPath, 0), options);
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("no eval scenarios found: " + scenariosPath);
        }
        agentLoop.start();
        waitForMcpLoad();

        Instant started = Instant.now();
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(started) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path artifactDir = resolveArtifactDir(options, runId);
        Path casesDir = artifactDir.resolve("cases");
        Files.createDirectories(casesDir);

        Map<String, Object> manifest = buildManifest(runId, started, scenariosPath, scenarios.size(), options);
        writeJson(artifactDir.resolve("manifest.json"), manifest);

        List<EvalCaseResult> results = new ArrayList<>();
        Path casesJsonl = artifactDir.resolve("cases.jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(casesJsonl, StandardCharsets.UTF_8)) {
            for (int i = 0; i < scenarios.size(); i++) {
                EvalScenario scenario = normalizeScenario(scenarios.get(i), i);
                EvalCaseResult result = runScenario(scenario, options, runId, casesDir);
                results.add(result);
                writer.write(MAPPER.writeValueAsString(result));
                writer.newLine();
                if (options.isFailFast() && isFailFastStop(result)) {
                    break;
                }
            }
        }

        Instant ended = Instant.now();
        EvalRunSummary summary = summarize(runId, started, ended, artifactDir, results);
        writeJson(artifactDir.resolve("summary.json"), summary);
        EvalReportWriter.write(artifactDir, summary, results, false);
        return summary;
    }

    private boolean isFailFastStop(EvalCaseResult result) {
        if (result == null) {
            return true;
        }
        String status = result.getStatus();
        return !"pass".equals(status)
                && !"xfail".equals(status)
                && !"skipped".equals(status);
    }

    private void waitForMcpLoad() {
        try {
            Map<String, Object> configured = config.getTools() != null ? config.getTools().getMcpServers() : Map.of();
            if (configured == null || configured.isEmpty()) {
                return;
            }
            Map<String, String> statuses = agentLoop.getMcpLoader().getServerStatuses();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                statuses = agentLoop.getMcpLoader().getServerStatuses();
                boolean statusesConnected = !statuses.isEmpty()
                        && statuses.values().stream().allMatch("connected"::equals);
                boolean hasMcpTools = agentLoop.getTools().toolNames().stream().anyMatch(name -> name.startsWith("mcp_"));
                if (statusesConnected && hasMcpTools) {
                    return;
                }
                Thread.sleep(50);
            }
        } catch (Exception ignored) {
        }
    }

    public static List<EvalScenario> loadScenarios(Path scenariosPath, int limit) throws Exception {
        List<EvalScenario> scenarios = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(scenariosPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                EvalScenario scenario;
                try {
                    scenario = MAPPER.readValue(trimmed, EvalScenario.class);
                } catch (Exception e) {
                    throw new IllegalArgumentException("invalid scenario JSON at line " + lineNo + ": " + e.getMessage(), e);
                }
                scenarios.add(scenario);
                if (limit > 0 && scenarios.size() >= limit) {
                    break;
                }
            }
        }
        return scenarios;
    }

    public EvalCaseResult runScenario(EvalScenario scenario, EvalOptions options, String runId, Path casesDir) throws Exception {
        agentLoop.start();
        waitForMcpLoad();
        EvalCaseResult result = new EvalCaseResult()
                .setId(scenario.getId())
                .setSessionKey(sessionKeyFor(scenario, options, runId));
        if (Boolean.TRUE.equals(scenario.getSkip())) {
            result.setStatus("skipped")
                    .setExpectationStatus("skipped")
                    .setExpectationReason(nonBlank(scenario.getSkipReason(), "scenario marked skip"))
                    .setResponse("");
            writeCaseArtifact(scenario, result, casesDir);
            return result;
        }
        if (recordingProvider != null) {
            recordingProvider.reset();
        }
        WorkspaceRestorePoint restorePoint = null;
        List<String> workspaceRestoreErrors = new ArrayList<>();
        if (shouldRestoreWorkspace(scenario, options)) {
            try {
                restorePoint = WorkspaceRestorePoint.create(config.getWorkspacePath(), List.of(casesDir.getParent()));
            } catch (Exception e) {
                workspaceRestoreErrors.add("failed to create workspace restore point: " + e.getMessage());
            }
        }
        Map<String, Object> sessionRestoreSnapshot = EvalRuntimeState.sessionRestoreSnapshot(
                agentLoop.getSessions(),
                result.getSessionKey()
        );
        result.setFixtureErrors(prepareWorkspace(scenario, options));
        WorkspaceDiff.Snapshot beforeWorkspace = WorkspaceDiff.capture(config.getWorkspacePath());
        long startedNanos = System.nanoTime();
        Exception failure = null;
        List<Map<String, Object>> turnResults = new ArrayList<>();
        List<String> aggregateToolsUsed = new ArrayList<>();
        Map<String, Object> lastRunTrace = null;
        Map<String, Object> lastContextTrace = null;

        try {
            List<EvalTurn> turns = turnsFor(scenario);
            for (int i = 0; i < turns.size(); i++) {
                EvalTurn turn = turns.get(i);
                OutboundMessage outbound = agentLoop.processDirect(
                        turn.getInput(),
                        result.getSessionKey(),
                        "eval",
                        scenario.getId(),
                        turnMetadata(scenario, turn, i),
                        List.of()
                );
                String response = outbound != null ? outbound.getContent() : "";
                result.setResponse(response);

                SessionTrace trace = readSessionTrace(result.getSessionKey());
                if (trace.runTrace() != null) {
                    lastRunTrace = trace.runTrace();
                }
                if (trace.contextTrace() != null) {
                    lastContextTrace = trace.contextTrace();
                }
                if (trace.toolsUsed() != null && !trace.toolsUsed().isEmpty()) {
                    aggregateToolsUsed.addAll(trace.toolsUsed());
                }
                turnResults.add(turnResult(i, turn, response, trace));
            }
        } catch (Exception e) {
            failure = e;
            if (result.getResponse() == null) {
                result.setResponse("");
            }
        }

        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
        WorkspaceDiff.Snapshot afterWorkspace = WorkspaceDiff.capture(config.getWorkspacePath());
        WorkspaceDiff.DiffResult workspaceDiff = WorkspaceDiff.diff(beforeWorkspace, afterWorkspace);
        result.setDurationMs(durationMs);
        result.setTurnResults(turnResults);
        if (!aggregateToolsUsed.isEmpty()) {
            result.setToolsUsed(distinct(aggregateToolsUsed));
        }
        result.setRunTrace(lastRunTrace);
        result.setContextTrace(lastContextTrace);
        result.setWorkspaceDiff(workspaceDiff.toMap());
        result.setSideEffectViolations(validateSideEffects(scenario, result, workspaceDiff));
        if (recordingProvider != null) {
            result.setModelCalls(recordingProvider.drainCalls());
        }
        if (result.getRunTrace() == null && result.getContextTrace() == null && result.getToolsUsed().isEmpty()) {
            attachSessionTrace(result);
        }
        result.setSessionState(EvalRuntimeState.sessionState(agentLoop.getSessions(), result.getSessionKey()));
        result.setMemoryState(EvalRuntimeState.memoryState(config.getWorkspacePath()));
        result.setAssertionErrors(assertScenario(scenario, result));
        if (scenario.getMaxDurationMs() != null && scenario.getMaxDurationMs() > 0 && durationMs > scenario.getMaxDurationMs()) {
            result.setMaxDurationExceededDetail(
                    "duration_ms=" + durationMs + " exceeded max_duration_ms=" + scenario.getMaxDurationMs()
            );
        }
        if (shouldRestoreSession(scenario, options)) {
            result.setSessionRestoreErrors(EvalRuntimeState.restoreSession(
                    agentLoop.getSessions(),
                    result.getSessionKey(),
                    sessionRestoreSnapshot
            ));
        }
        if (restorePoint != null) {
            workspaceRestoreErrors.addAll(restorePoint.restore());
        }
        result.setWorkspaceRestoreErrors(workspaceRestoreErrors);

        EvalFailureClassifier.Failure classified = EvalFailureClassifier.classify(result, failure);
        if (classified == null) {
            result.setStatus("pass");
        } else {
            result.setStatus("fail")
                    .setFailureKind(classified.kind())
                    .setFailureDetail(classified.detail());
        }
        applyExpectedFailure(scenario, result);

        writeCaseArtifact(scenario, result, casesDir);
        return result;
    }

    private boolean shouldRestoreWorkspace(EvalScenario scenario, EvalOptions options) {
        if (scenario != null && scenario.getRestoreWorkspace() != null) {
            return Boolean.TRUE.equals(scenario.getRestoreWorkspace());
        }
        return options == null || options.isRestoreWorkspace();
    }

    private boolean shouldRestoreSession(EvalScenario scenario, EvalOptions options) {
        if (scenario != null && scenario.getRestoreSession() != null) {
            return Boolean.TRUE.equals(scenario.getRestoreSession());
        }
        return options == null || options.isRestoreSession();
    }

    private void writeCaseArtifact(EvalScenario scenario, EvalCaseResult result, Path casesDir) throws Exception {
        Path casePath = casesDir.resolve(safeFileName(scenario.getId()) + ".json");
        result.setArtifactPath(casePath.toAbsolutePath().normalize().toString());
        Map<String, Object> caseArtifact = new LinkedHashMap<>();
        caseArtifact.put("scenario", MAPPER.convertValue(scenario, MAP_TYPE));
        caseArtifact.put("result", MAPPER.convertValue(result, MAP_TYPE));
        writeJson(casePath, caseArtifact);
    }

    private void applyExpectedFailure(EvalScenario scenario, EvalCaseResult result) {
        if (!Boolean.TRUE.equals(scenario.getXfail())) {
            return;
        }
        String expectedKind = scenario.getExpectedFailureKind();
        if ("fail".equals(result.getStatus())) {
            if (expectedKind != null && !expectedKind.isBlank()
                    && !expectedKind.equals(result.getFailureKind())) {
                result.setExpectationStatus("xfail_kind_mismatch")
                        .setExpectationReason("expected failure kind " + expectedKind
                                + " but got " + result.getFailureKind());
                return;
            }
            result.setStatus("xfail")
                    .setExpectationStatus("expected_failure")
                    .setExpectationReason(nonBlank(scenario.getXfailReason(), "scenario marked xfail"));
            return;
        }
        if ("pass".equals(result.getStatus())) {
            result.setStatus("xpass")
                    .setFailureKind("unexpected_pass")
                    .setFailureDetail(nonBlank(scenario.getXfailReason(), "xfail scenario passed unexpectedly"))
                    .setExpectationStatus("unexpected_pass")
                    .setExpectationReason(nonBlank(scenario.getXfailReason(), "scenario marked xfail"));
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private List<EvalScenario> applyScenarioFilters(List<EvalScenario> scenarios, EvalOptions options) {
        List<String> includeTags = normalizeTags(options != null ? options.getIncludeTags() : List.of());
        List<String> excludeTags = normalizeTags(options != null ? options.getExcludeTags() : List.of());
        int limit = options != null ? options.getLimit() : 0;
        List<EvalScenario> filtered = new ArrayList<>();
        for (EvalScenario scenario : scenarios != null ? scenarios : List.<EvalScenario>of()) {
            List<String> scenarioTags = normalizeTags(scenario.getTags());
            if (!includeTags.isEmpty() && scenarioTags.stream().noneMatch(includeTags::contains)) {
                continue;
            }
            if (!excludeTags.isEmpty() && scenarioTags.stream().anyMatch(excludeTags::contains)) {
                continue;
            }
            filtered.add(scenario);
            if (limit > 0 && filtered.size() >= limit) {
                break;
            }
        }
        return filtered;
    }

    private List<String> normalizeTags(List<String> tags) {
        List<String> out = new ArrayList<>();
        for (String tag : tags != null ? tags : List.<String>of()) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String normalized = tag.trim().toLowerCase(Locale.ROOT);
            if (!out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    private List<String> prepareWorkspace(EvalScenario scenario, EvalOptions options) {
        List<String> errors = new ArrayList<>();
        Path workspace = config.getWorkspacePath();
        if (workspace == null) {
            errors.add("workspace is not configured");
            return errors;
        }
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            errors.add("failed to create workspace: " + e.getMessage());
            return errors;
        }

        if (Boolean.TRUE.equals(scenario.getCleanWorkspace())) {
            boolean allowUnsafeClean = options != null && options.isAllowUnsafeWorkspaceClean();
            if (!isSafeWorkspaceForCleaning(workspace) && !allowUnsafeClean) {
                errors.add("refusing to clean unsafe workspace: " + workspace
                        + " (use a temp/target workspace, create .ricbot-eval-workspace, or pass --allow-unsafe-workspace-clean)");
                return errors;
            }
            try {
                cleanWorkspace(workspace);
            } catch (Exception e) {
                errors.add("failed to clean workspace: " + e.getMessage());
                return errors;
            }
        }

        Map<String, String> fixtureFiles = scenario.getWorkspaceFiles();
        if (fixtureFiles == null || fixtureFiles.isEmpty()) {
            return errors;
        }
        for (Map.Entry<String, String> entry : fixtureFiles.entrySet()) {
            try {
                Path target = resolveWorkspacePath(workspace, entry.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue() != null ? entry.getValue() : "", StandardCharsets.UTF_8);
            } catch (Exception e) {
                errors.add("failed to write fixture file " + entry.getKey() + ": " + e.getMessage());
            }
        }
        return errors;
    }

    private void cleanWorkspace(Path workspace) throws Exception {
        if (!Files.exists(workspace)) {
            return;
        }
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(workspace)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (".git".equals(name) || ".idea".equals(name) || ".ricbot".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                if (file.getFileName() != null && ".ricbot-eval-workspace".equals(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
                if (!dir.equals(workspace)) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!".git".equals(name) && !".idea".equals(name) && !".ricbot".equals(name)) {
                        Files.deleteIfExists(dir);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isSafeWorkspaceForCleaning(Path workspace) {
        if (workspace == null) {
            return false;
        }
        Path root = workspace.toAbsolutePath().normalize();
        if (Files.exists(root.resolve(".ricbot-eval-workspace"))) {
            return true;
        }
        Path tmp = Path.of(System.getProperty("java.io.tmpdir", "")).toAbsolutePath().normalize();
        if (!tmp.toString().isBlank() && root.startsWith(tmp)) {
            return true;
        }
        for (Path part : root) {
            if ("target".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private List<String> validateSideEffects(EvalScenario scenario, EvalCaseResult result, WorkspaceDiff.DiffResult diff) {
        List<String> violations = new ArrayList<>();
        int changeCount = diff != null ? diff.changeCount() : 0;
        String policy = scenario.getAllowedSideEffects();
        if (policy != null && !policy.isBlank()) {
            String normalized = policy.trim().toLowerCase(Locale.ROOT);
            boolean allowsFiles = normalized.equals("any")
                    || normalized.equals("all")
                    || normalized.contains("files")
                    || normalized.contains("file_write")
                    || normalized.contains("write");
            boolean disallowsFiles = normalized.equals("none")
                    || normalized.equals("read_only")
                    || normalized.equals("readonly")
                    || normalized.equals("no_files");
            boolean allowsAll = normalized.equals("any") || normalized.equals("all");
            if (changeCount > 0 && disallowsFiles) {
                violations.add("file changes are not allowed by allowed_side_effects=" + policy);
            } else if (changeCount > 0 && !allowsFiles) {
                violations.add("file changes are not explicitly allowed by allowed_side_effects=" + policy);
            }
            if (!allowsAll) {
                for (Map<String, Object> event : toolCallEvents(result)) {
                    String tool = String.valueOf(event.getOrDefault("name", ""));
                    if (tool.isBlank()) {
                        continue;
                    }
                    boolean readOnly = Boolean.TRUE.equals(event.get("read_only"));
                    if (disallowsFiles && !readOnly) {
                        violations.add("non-read-only tool is not allowed by allowed_side_effects="
                                + policy + ": " + tool);
                        continue;
                    }
                    String category = sideEffectCategory(tool);
                    if (!allowsCategory(normalized, category, tool)) {
                        violations.add("tool side effect category '" + category
                                + "' is not allowed by allowed_side_effects=" + policy + ": " + tool);
                    }
                }
            }
        }

        if (scenario.getMaxFileChanges() != null && scenario.getMaxFileChanges() >= 0 && changeCount > scenario.getMaxFileChanges()) {
            violations.add("file change count " + changeCount + " exceeds max_file_changes=" + scenario.getMaxFileChanges());
        }
        return violations;
    }

    private List<Map<String, Object>> toolCallEvents(EvalCaseResult result) {
        List<Map<String, Object>> events = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (result == null) {
            return events;
        }
        collectToolEvents(result.getRunTrace(), events, seen);
        for (Map<String, Object> turnResult : result.getTurnResults()) {
            Object rawRunTrace = turnResult.get("run_trace");
            if (rawRunTrace instanceof Map<?, ?> map) {
                collectToolEvents(copyMap(map), events, seen);
            }
        }
        return events;
    }

    private void collectToolEvents(Map<String, Object> runTrace, List<Map<String, Object>> out, java.util.Set<String> seen) {
        if (runTrace == null) {
            return;
        }
        Object rawEvents = runTrace.get("events");
        if (!(rawEvents instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> event && "tool_call".equals(String.valueOf(event.get("type")))) {
                Object rawId = event.get("tool_call_id");
                String id = rawId != null ? String.valueOf(rawId) : "";
                String key = !id.isBlank()
                        ? id
                        : String.valueOf(event.get("iteration")) + ":" + String.valueOf(event.get("name"));
                if (seen.add(key)) {
                    out.add(copyMap(event));
                }
            }
        }
    }

    private String sideEffectCategory(String tool) {
        String name = tool != null ? tool.toLowerCase(Locale.ROOT) : "";
        if (name.equals("write_file") || name.equals("edit_file") || name.equals("notebook_edit")) {
            return "files";
        }
        if (name.equals("exec") || name.equals("spawn")) {
            return "process";
        }
        if (name.startsWith("web_")) {
            return "network";
        }
        if (name.equals("cron")) {
            return "cron";
        }
        if (name.startsWith("mcp_")) {
            return "mcp";
        }
        return "read_only";
    }

    private boolean allowsCategory(String normalizedPolicy, String category, String tool) {
        if ("read_only".equals(category)) {
            return true;
        }
        if ("files".equals(category)) {
            return normalizedPolicy.contains("files")
                    || normalizedPolicy.contains("file_write")
                    || normalizedPolicy.contains("write");
        }
        if ("process".equals(category)) {
            return normalizedPolicy.contains("process")
                    || normalizedPolicy.contains("exec")
                    || normalizedPolicy.contains("shell")
                    || normalizedPolicy.contains("spawn");
        }
        if ("network".equals(category)) {
            return normalizedPolicy.contains("network")
                    || normalizedPolicy.contains("web")
                    || normalizedPolicy.contains("http");
        }
        if ("cron".equals(category)) {
            return normalizedPolicy.contains("cron")
                    || normalizedPolicy.contains("schedule");
        }
        if ("mcp".equals(category)) {
            return normalizedPolicy.contains("mcp")
                    || normalizedPolicy.contains(tool.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private List<EvalTurn> turnsFor(EvalScenario scenario) {
        if (scenario.getTurns() != null && !scenario.getTurns().isEmpty()) {
            return scenario.getTurns();
        }
        return List.of(new EvalTurn()
                .setInput(scenario.getInput())
                .setMetadata(scenario.getMetadata()));
    }

    private Map<String, Object> turnMetadata(EvalScenario scenario, EvalTurn turn, int index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (scenario.getMetadata() != null) {
            metadata.putAll(scenario.getMetadata());
        }
        if (turn.getMetadata() != null) {
            metadata.putAll(turn.getMetadata());
        }
        metadata.put("eval_turn_index", index);
        return metadata;
    }

    private Map<String, Object> turnResult(int index, EvalTurn turn, String response, SessionTrace trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        out.put("input", turn.getInput());
        out.put("response", response != null ? response : "");
        out.put("tools_used", trace.toolsUsed() != null ? trace.toolsUsed() : List.of());
        if (trace.runTrace() != null) {
            out.put("run_trace", trace.runTrace());
        }
        if (trace.contextTrace() != null) {
            out.put("context_trace", trace.contextTrace());
        }
        return out;
    }

    private SessionTrace readSessionTrace(String sessionKey) {
        try {
            Session session = agentLoop.getSessions().getOrCreate(sessionKey);
            Map<String, Object> metadata = session.getMetadata() != null ? session.getMetadata() : Map.of();
            Map<String, Object> runTrace = null;
            Map<String, Object> contextTrace = null;
            List<String> toolsUsed = List.of();
            Object rawRunTrace = metadata.get(SessionRuntimeKeys.RUN_TRACE_KEY);
            Object rawContextTrace = metadata.get(SessionRuntimeKeys.CONTEXT_TRACE_KEY);
            if (rawRunTrace instanceof Map<?, ?> map) {
                runTrace = copyMap(map);
                Object rawToolsUsed = map.get("tools_used");
                if (rawToolsUsed instanceof List<?> list) {
                    toolsUsed = list.stream().map(String::valueOf).toList();
                }
            }
            if (rawContextTrace instanceof Map<?, ?> map) {
                contextTrace = copyMap(map);
            }
            return new SessionTrace(runTrace, contextTrace, toolsUsed);
        } catch (Exception ignored) {
            return new SessionTrace(null, null, List.of());
        }
    }

    private static List<String> distinct(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values != null ? values : List.<String>of()) {
            if (value != null && !out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    private void attachSessionTrace(EvalCaseResult result) {
        try {
            Session session = agentLoop.getSessions().getOrCreate(result.getSessionKey());
            Map<String, Object> metadata = session.getMetadata() != null ? session.getMetadata() : Map.of();
            Object runTrace = metadata.get(SessionRuntimeKeys.RUN_TRACE_KEY);
            Object contextTrace = metadata.get(SessionRuntimeKeys.CONTEXT_TRACE_KEY);
            if (runTrace instanceof Map<?, ?> map) {
                result.setRunTrace(copyMap(map));
                Object toolsUsed = map.get("tools_used");
                if (toolsUsed instanceof List<?> list) {
                    result.setToolsUsed(list.stream().map(String::valueOf).toList());
                }
            }
            if (contextTrace instanceof Map<?, ?> map) {
                result.setContextTrace(copyMap(map));
            }
        } catch (Exception ignored) {
        }
    }

    private record SessionTrace(Map<String, Object> runTrace, Map<String, Object> contextTrace, List<String> toolsUsed) {
    }

    private List<String> assertScenario(EvalScenario scenario, EvalCaseResult result) {
        List<String> errors = new ArrayList<>();
        String response = result.getResponse();
        String actual = response != null ? response : "";
        boolean ignoreCase = !Boolean.FALSE.equals(scenario.getIgnoreCase());
        String comparable = ignoreCase ? actual.toLowerCase(Locale.ROOT) : actual;

        for (String expected : scenario.getExpectedContains()) {
            if (expected == null) {
                continue;
            }
            String needle = ignoreCase ? expected.toLowerCase(Locale.ROOT) : expected;
            if (!comparable.contains(needle)) {
                errors.add("missing expected text: " + expected);
            }
        }
        for (String unexpected : scenario.getExpectedNotContains()) {
            if (unexpected == null) {
                continue;
            }
            String needle = ignoreCase ? unexpected.toLowerCase(Locale.ROOT) : unexpected;
            if (comparable.contains(needle)) {
                errors.add("unexpected text present: " + unexpected);
            }
        }
        for (String regex : scenario.getExpectedRegex()) {
            if (regex == null || regex.isBlank()) {
                continue;
            }
            Pattern pattern = ignoreCase
                    ? Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
                    : Pattern.compile(regex, Pattern.DOTALL);
            if (!pattern.matcher(actual).find()) {
                errors.add("missing expected regex: " + regex);
            }
        }
        errors.addAll(assertJson("response", actual, scenario.getExpectedJsonRequired(),
                scenario.getExpectedJsonAbsent(), scenario.getExpectedJsonValues()));

        for (String expectedTool : scenario.getExpectedTools()) {
            if (expectedTool == null || expectedTool.isBlank()) {
                continue;
            }
            if (!result.getToolsUsed().contains(expectedTool)) {
                errors.add("missing expected tool: " + expectedTool);
            }
        }
        for (String forbiddenTool : scenario.getForbiddenTools()) {
            if (forbiddenTool == null || forbiddenTool.isBlank()) {
                continue;
            }
            if (result.getToolsUsed().contains(forbiddenTool)) {
                errors.add("forbidden tool used: " + forbiddenTool);
            }
        }

        if (scenario.getMaxModelCalls() != null && scenario.getMaxModelCalls() >= 0
                && result.getModelCalls().size() > scenario.getMaxModelCalls()) {
            errors.add("model call count " + result.getModelCalls().size()
                    + " exceeds max_model_calls=" + scenario.getMaxModelCalls());
        }

        int toolCallCount = toolCallCount(result);
        if (scenario.getMaxToolCalls() != null && scenario.getMaxToolCalls() >= 0
                && toolCallCount > scenario.getMaxToolCalls()) {
            errors.add("tool call count " + toolCallCount
                    + " exceeds max_tool_calls=" + scenario.getMaxToolCalls());
        }

        if (scenario.getExpectedStopReason() != null && !scenario.getExpectedStopReason().isBlank()) {
            String actualStopReason = result.getRunTrace() != null
                    ? String.valueOf(result.getRunTrace().getOrDefault("stop_reason", ""))
                    : "";
            if (!scenario.getExpectedStopReason().equals(actualStopReason)) {
                errors.add("expected stop_reason=" + scenario.getExpectedStopReason()
                        + " but got " + actualStopReason);
            }
        }
        errors.addAll(assertTurnExpectations(scenario, result));
        errors.addAll(assertFileExpectations(scenario));
        errors.addAll(assertSessionExpectations(scenario, result));
        errors.addAll(assertMemoryExpectations(scenario));
        return errors;
    }

    private List<String> assertSessionExpectations(EvalScenario scenario, EvalCaseResult result) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> state = result.getSessionState() != null ? result.getSessionState() : Map.of();
        if (scenario.getExpectedSessionMessageCount() != null) {
            int actual = intValue(state.get("message_count"), -1);
            if (actual != scenario.getExpectedSessionMessageCount()) {
                errors.add("expected session message_count=" + scenario.getExpectedSessionMessageCount()
                        + " but got " + actual);
            }
        }
        Map<String, Integer> roleCounts = intMap(state.get("role_counts"));
        for (Map.Entry<String, Integer> entry : scenario.getExpectedSessionRoleCounts() != null
                ? scenario.getExpectedSessionRoleCounts().entrySet()
                : Map.<String, Integer>of().entrySet()) {
            int actual = roleCounts.getOrDefault(entry.getKey(), 0);
            if (actual != entry.getValue()) {
                errors.add("expected session role_count " + entry.getKey() + "=" + entry.getValue()
                        + " but got " + actual);
            }
        }
        String content = sessionContent(result.getSessionKey());
        errors.addAll(assertText("session", content, scenario.getExpectedSessionContains(),
                scenario.getExpectedSessionNotContains(), List.of(), !Boolean.FALSE.equals(scenario.getIgnoreCase())));
        return errors;
    }

    private List<String> assertMemoryExpectations(EvalScenario scenario) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> state = EvalRuntimeState.memoryState(config.getWorkspacePath());
        Map<String, Integer> counts = intMap(state);
        for (Map.Entry<String, Integer> entry : scenario.getExpectedMemoryCounts() != null
                ? scenario.getExpectedMemoryCounts().entrySet()
                : Map.<String, Integer>of().entrySet()) {
            int actual = counts.getOrDefault(entry.getKey(), 0);
            if (actual != entry.getValue()) {
                errors.add("expected memory " + entry.getKey() + "=" + entry.getValue()
                        + " but got " + actual);
            }
        }
        assertMemoryFileText(scenario.getExpectedMemoryFileContains(), true, errors);
        assertMemoryFileText(scenario.getExpectedMemoryFileNotContains(), false, errors);
        return errors;
    }

    private void assertMemoryFileText(Map<String, List<String>> expectations, boolean shouldContain, List<String> errors) {
        if (expectations == null || expectations.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : expectations.entrySet()) {
            String relative = entry.getKey();
            String content = EvalRuntimeState.readMemoryText(config.getWorkspacePath(), relative);
            if (content.isEmpty() && shouldContain) {
                errors.add("memory file is missing or empty: " + relative);
                continue;
            }
            for (String needle : entry.getValue() != null ? entry.getValue() : List.<String>of()) {
                boolean present = needle != null && content.contains(needle);
                if (shouldContain && !present) {
                    errors.add("memory file " + relative + " is missing expected text: " + needle);
                } else if (!shouldContain && present) {
                    errors.add("memory file " + relative + " contains forbidden text: " + needle);
                }
            }
        }
    }

    private String sessionContent(String sessionKey) {
        try {
            Session session = agentLoop.getSessions().getOrCreate(sessionKey);
            StringBuilder out = new StringBuilder();
            for (Map<String, Object> message : session.getMessages()) {
                Object content = message.get("content");
                if (content != null) {
                    out.append(content).append('\n');
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int intValue(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }

    private static Map<String, Integer> intMap(Object raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() instanceof Number n) {
                out.put(String.valueOf(entry.getKey()), n.intValue());
            }
        }
        return out;
    }

    private List<String> assertTurnExpectations(EvalScenario scenario, EvalCaseResult result) {
        List<String> errors = new ArrayList<>();
        if (scenario.getTurns() == null || scenario.getTurns().isEmpty()) {
            return errors;
        }
        List<Map<String, Object>> turnResults = result.getTurnResults();
        for (int i = 0; i < scenario.getTurns().size(); i++) {
            EvalTurn turn = scenario.getTurns().get(i);
            Map<String, Object> turnResult = i < turnResults.size() ? turnResults.get(i) : Map.of();
            String response = String.valueOf(turnResult.getOrDefault("response", ""));
            List<String> toolsUsed = toolsUsedFromTurn(turnResult);
            errors.addAll(assertText("turn " + i, response, turn.getExpectedContains(), turn.getExpectedNotContains(),
                    turn.getExpectedRegex(), !Boolean.FALSE.equals(scenario.getIgnoreCase())));
            errors.addAll(assertJson("turn " + i + " response", response, turn.getExpectedJsonRequired(),
                    turn.getExpectedJsonAbsent(), turn.getExpectedJsonValues()));
            for (String expectedTool : turn.getExpectedTools()) {
                if (expectedTool != null && !expectedTool.isBlank() && !toolsUsed.contains(expectedTool)) {
                    errors.add("turn " + i + " missing expected tool: " + expectedTool);
                }
            }
            for (String forbiddenTool : turn.getForbiddenTools()) {
                if (forbiddenTool != null && !forbiddenTool.isBlank() && toolsUsed.contains(forbiddenTool)) {
                    errors.add("turn " + i + " forbidden tool used: " + forbiddenTool);
                }
            }
            if (turn.getExpectedStopReason() != null && !turn.getExpectedStopReason().isBlank()) {
                String stopReason = "";
                Object rawRunTrace = turnResult.get("run_trace");
                if (rawRunTrace instanceof Map<?, ?> runTrace) {
                    Object rawStopReason = runTrace.get("stop_reason");
                    stopReason = rawStopReason != null ? String.valueOf(rawStopReason) : "";
                }
                if (!turn.getExpectedStopReason().equals(stopReason)) {
                    errors.add("turn " + i + " expected stop_reason=" + turn.getExpectedStopReason()
                            + " but got " + stopReason);
                }
            }
        }
        return errors;
    }

    private List<String> assertJson(
            String scope,
            String response,
            List<String> requiredPaths,
            List<String> absentPaths,
            Map<String, Object> expectedValues
    ) {
        List<String> errors = new ArrayList<>();
        boolean hasJsonExpectations = (requiredPaths != null && !requiredPaths.isEmpty())
                || (absentPaths != null && !absentPaths.isEmpty())
                || (expectedValues != null && !expectedValues.isEmpty());
        if (!hasJsonExpectations) {
            return errors;
        }

        Object json;
        try {
            json = MAPPER.readValue(response != null ? response : "", Object.class);
        } catch (Exception e) {
            errors.add(scope + " is not valid JSON: " + e.getMessage());
            return errors;
        }

        for (String path : requiredPaths != null ? requiredPaths : List.<String>of()) {
            if (path == null || path.isBlank()) {
                continue;
            }
            if (missingJsonPath(json, path)) {
                errors.add(scope + " missing expected JSON path: " + path);
            }
        }
        for (String path : absentPaths != null ? absentPaths : List.<String>of()) {
            if (path == null || path.isBlank()) {
                continue;
            }
            if (!missingJsonPath(json, path)) {
                errors.add(scope + " unexpected JSON path present: " + path);
            }
        }
        for (Map.Entry<String, Object> entry : expectedValues != null ? expectedValues.entrySet() : Map.<String, Object>of().entrySet()) {
            String path = entry.getKey();
            if (path == null || path.isBlank()) {
                continue;
            }
            JsonPathValue actual = readJsonPath(json, path);
            if (!actual.found()) {
                errors.add(scope + " missing expected JSON path: " + path);
            } else if (!jsonValuesEqual(entry.getValue(), actual.value())) {
                errors.add(scope + " JSON path " + path + " expected " + entry.getValue()
                        + " but got " + actual.value());
            }
        }
        return errors;
    }

    private boolean missingJsonPath(Object json, String path) {
        return !readJsonPath(json, path).found();
    }

    private JsonPathValue readJsonPath(Object json, String path) {
        Object current = json;
        for (String rawPart : path.split("\\.")) {
            if (rawPart.isBlank()) {
                return new JsonPathValue(false, null);
            }
            PathPart part = parsePathPart(rawPart);
            if (part.name() != null && !part.name().isBlank()) {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(part.name())) {
                    return new JsonPathValue(false, null);
                }
                current = map.get(part.name());
            }
            if (part.index() != null) {
                if (!(current instanceof List<?> list)) {
                    return new JsonPathValue(false, null);
                }
                int index = part.index();
                if (index < 0 || index >= list.size()) {
                    return new JsonPathValue(false, null);
                }
                current = list.get(index);
            }
        }
        return new JsonPathValue(true, current);
    }

    private PathPart parsePathPart(String rawPart) {
        int bracket = rawPart.indexOf('[');
        if (bracket < 0 || !rawPart.endsWith("]")) {
            return new PathPart(rawPart, null);
        }
        String name = rawPart.substring(0, bracket);
        String rawIndex = rawPart.substring(bracket + 1, rawPart.length() - 1);
        try {
            return new PathPart(name, Integer.parseInt(rawIndex));
        } catch (NumberFormatException e) {
            return new PathPart(rawPart, null);
        }
    }

    private boolean jsonValuesEqual(Object expected, Object actual) {
        if (expected instanceof Number e && actual instanceof Number a) {
            return Double.compare(e.doubleValue(), a.doubleValue()) == 0;
        }
        return java.util.Objects.equals(expected, actual);
    }

    private record JsonPathValue(boolean found, Object value) {
    }

    private record PathPart(String name, Integer index) {
    }

    private List<String> assertText(
            String scope,
            String actual,
            List<String> expectedContains,
            List<String> expectedNotContains,
            List<String> expectedRegex,
            boolean ignoreCase
    ) {
        List<String> errors = new ArrayList<>();
        String safeActual = actual != null ? actual : "";
        String comparable = ignoreCase ? safeActual.toLowerCase(Locale.ROOT) : safeActual;
        for (String expected : expectedContains != null ? expectedContains : List.<String>of()) {
            if (expected == null) {
                continue;
            }
            String needle = ignoreCase ? expected.toLowerCase(Locale.ROOT) : expected;
            if (!comparable.contains(needle)) {
                errors.add(scope + " missing expected text: " + expected);
            }
        }
        for (String unexpected : expectedNotContains != null ? expectedNotContains : List.<String>of()) {
            if (unexpected == null) {
                continue;
            }
            String needle = ignoreCase ? unexpected.toLowerCase(Locale.ROOT) : unexpected;
            if (comparable.contains(needle)) {
                errors.add(scope + " unexpected text present: " + unexpected);
            }
        }
        for (String regex : expectedRegex != null ? expectedRegex : List.<String>of()) {
            if (regex == null || regex.isBlank()) {
                continue;
            }
            Pattern pattern = ignoreCase
                    ? Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
                    : Pattern.compile(regex, Pattern.DOTALL);
            if (!pattern.matcher(safeActual).find()) {
                errors.add(scope + " missing expected regex: " + regex);
            }
        }
        return errors;
    }

    private List<String> toolsUsedFromTurn(Map<String, Object> turnResult) {
        Object rawTools = turnResult.get("tools_used");
        if (!(rawTools instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private List<String> assertFileExpectations(EvalScenario scenario) {
        List<String> errors = new ArrayList<>();
        assertFileContains(scenario.getExpectedFileContains(), true, errors);
        assertFileContains(scenario.getExpectedFileNotContains(), false, errors);
        return errors;
    }

    private void assertFileContains(Map<String, List<String>> expectations, boolean shouldContain, List<String> errors) {
        if (expectations == null || expectations.isEmpty()) {
            return;
        }
        Path workspace = config.getWorkspacePath();
        for (Map.Entry<String, List<String>> entry : expectations.entrySet()) {
            String relativePath = entry.getKey();
            Path target;
            try {
                target = resolveWorkspacePath(workspace, relativePath);
            } catch (Exception e) {
                errors.add("invalid expected file path " + relativePath + ": " + e.getMessage());
                continue;
            }
            if (!Files.exists(target)) {
                if (shouldContain) {
                    errors.add("expected file is missing: " + relativePath);
                }
                continue;
            }
            String content;
            try {
                content = Files.readString(target, StandardCharsets.UTF_8);
            } catch (Exception e) {
                errors.add("failed to read expected file " + relativePath + ": " + e.getMessage());
                continue;
            }
            for (String needle : entry.getValue() != null ? entry.getValue() : List.<String>of()) {
                if (needle == null) {
                    continue;
                }
                boolean present = content.contains(needle);
                if (shouldContain && !present) {
                    errors.add("file " + relativePath + " is missing expected text: " + needle);
                } else if (!shouldContain && present) {
                    errors.add("file " + relativePath + " contains forbidden text: " + needle);
                }
            }
        }
    }

    private static Path resolveWorkspacePath(Path workspace, String relativePath) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace is not configured");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path is blank");
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace");
        }
        return target;
    }

    private int toolCallCount(EvalCaseResult result) {
        if (result.getRunTrace() == null) {
            return result.getToolsUsed().size();
        }
        Object events = result.getRunTrace().get("events");
        if (!(events instanceof List<?> list)) {
            return result.getToolsUsed().size();
        }
        int count = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> event && "tool_call".equals(String.valueOf(event.get("type")))) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> buildManifest(String runId, Instant started, Path scenariosPath, int scenarioCount, EvalOptions options) {
        Config.AgentDefaults defaults = config.getAgents().getDefaults();
        Path workspace = config.getWorkspacePath();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_id", runId);
        manifest.put("started_at", started.toString());
        manifest.put("scenarios_path", scenariosPath.toString());
        manifest.put("scenario_count", scenarioCount);
        manifest.put("workspace", workspace.toString());
        manifest.put("model", defaults.getModel());
        manifest.put("provider", config.getProviderName(defaults.getModel()));
        if (recordingProvider != null && recordingProvider.isRecordingSmokeProvider()) {
            manifest.put("provider_mode", EvalSmokeRuntime.PROVIDER_MODE);
        }
        manifest.put("context_window_tokens", defaults.getContextWindowTokens());
        manifest.put("max_tool_iterations", defaults.getMaxToolIterations());
        manifest.put("max_tool_result_chars", defaults.getMaxToolResultChars());
        manifest.put("tool_schema_sha256", sha256Json(agentLoop.getTools().getDefinitions()));
        manifest.put("workspace_snapshot_before", WorkspaceSnapshot.capture(workspace));
        manifest.put("include_tags", normalizeTags(options != null ? options.getIncludeTags() : List.of()));
        manifest.put("exclude_tags", normalizeTags(options != null ? options.getExcludeTags() : List.of()));
        manifest.put("restore_workspace", options == null || options.isRestoreWorkspace());
        manifest.put("restore_session", options == null || options.isRestoreSession());
        return manifest;
    }

    private EvalRunSummary summarize(
            String runId,
            Instant started,
            Instant ended,
            Path artifactDir,
            List<EvalCaseResult> results
    ) throws Exception {
        Map<String, Integer> failuresByKind = new LinkedHashMap<>();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int expectedFailed = 0;
        int unexpectedPassed = 0;
        for (EvalCaseResult result : results) {
            String status = result.getStatus();
            switch (status != null ? status : "") {
                case "pass" -> passed++;
                case "skipped" -> skipped++;
                case "xfail" -> expectedFailed++;
                case "xpass" -> {
                    unexpectedPassed++;
                    failed++;
                    String kind = result.getFailureKind() != null ? result.getFailureKind() : "unexpected_pass";
                    failuresByKind.merge(kind, 1, Integer::sum);
                }
                default -> {
                    failed++;
                    String kind = result.getFailureKind() != null ? result.getFailureKind() : "unknown";
                    failuresByKind.merge(kind, 1, Integer::sum);
                }
            }
        }

        Map<String, Object> afterSnapshot = WorkspaceSnapshot.capture(config.getWorkspacePath());
        writeJson(artifactDir.resolve("workspace-after.json"), afterSnapshot);

        EvalRunSummary summary = new EvalRunSummary()
                .setRunId(runId)
                .setStartedAt(started.toString())
                .setEndedAt(ended.toString())
                .setTotal(results.size())
                .setPassed(passed)
                .setFailed(failed)
                .setSkipped(skipped)
                .setExpectedFailed(expectedFailed)
                .setUnexpectedPassed(unexpectedPassed)
                .setDurationMs(Math.max(0, ended.toEpochMilli() - started.toEpochMilli()))
                .setFailuresByKind(failuresByKind)
                .setArtifactDir(artifactDir.toAbsolutePath().normalize().toString());
        return EvalSummarySupport.enrich(summary, results);
    }

    private EvalScenario normalizeScenario(EvalScenario scenario, int index) {
        if (scenario.getId() == null || scenario.getId().isBlank()) {
            scenario.setId("case-" + String.format(Locale.ROOT, "%03d", index + 1));
        }
        if (scenario.getTurns() != null && !scenario.getTurns().isEmpty()) {
            for (int i = 0; i < scenario.getTurns().size(); i++) {
                EvalTurn turn = scenario.getTurns().get(i);
                if (turn == null || turn.getInput() == null || turn.getInput().isBlank()) {
                    throw new IllegalArgumentException("scenario turn input is required: " + scenario.getId() + "[" + i + "]");
                }
            }
        } else if (scenario.getInput() == null || scenario.getInput().isBlank()) {
            throw new IllegalArgumentException("scenario input is required: " + scenario.getId());
        }
        return scenario;
    }

    private String sessionKeyFor(EvalScenario scenario, EvalOptions options, String runId) {
        if (scenario.getSession() != null && !scenario.getSession().isBlank()) {
            return scenario.getSession();
        }
        String prefix = options.getSessionPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "eval:" + runId;
        }
        return prefix + ":" + scenario.getId();
    }

    private Path resolveArtifactDir(EvalOptions options, String runId) {
        Path outputDir = options.getOutputDir();
        if (outputDir == null) {
            outputDir = config.getWorkspacePath().resolve(".ricbot").resolve("evals");
        }
        return outputDir.toAbsolutePath().normalize().resolve(runId);
    }

    private static String safeFileName(String value) {
        String raw = value != null && !value.isBlank() ? value : "case";
        return raw.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static Map<String, Object> copyMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static String sha256Json(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] json = MAPPER.writeValueAsBytes(value);
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (Exception e) {
            return "";
        }
    }
}
