package ricbot.domain.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TeamPlan;
import ricbot.infra.execution.ExecutionBackend;
import ricbot.infra.execution.ExecutionRequest;
import ricbot.infra.execution.ExecutionResult;
import ricbot.infra.execution.LocalExecutionBackend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executes deterministic verification stages and commits bounded evidence to the runtime database. */
public final class WorkspaceVerificationService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final Path trustedWorkspace;
    private final ExecutionBackend backend;
    private final ricbot.infra.runtime.SqliteRuntimeStore runtime;

    public WorkspaceVerificationService(Path trustedWorkspace) {
        this(trustedWorkspace, new LocalExecutionBackend());
    }
    public WorkspaceVerificationService(Path trustedWorkspace, ExecutionBackend backend) {
        this.trustedWorkspace = trustedWorkspace.toAbsolutePath().normalize();
        this.backend = backend != null ? backend : new LocalExecutionBackend();
        this.runtime = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(this.trustedWorkspace);
    }

    public VerificationReport verify(String runId, Path integrationWorkspace, TeamPlan plan,
                                     List<TaskResult> results) {
        return verify(runId, integrationWorkspace, plan, results, "");
    }

    public VerificationReport verify(String runId, Path integrationWorkspace, TeamPlan plan,
                                     List<TaskResult> results, String expectedProfileDigest) {
        Path target = integrationWorkspace.toAbsolutePath().normalize();
        VerificationProfile profile = VerificationProfile.load(trustedWorkspace);
        String reportId = "verify-" + UUID.randomUUID();
        String artifactRef = "sqlite:.ricbot/runtime.db#verification/" + reportId;
        if (expectedProfileDigest != null && !expectedProfileDigest.isBlank()
                && !expectedProfileDigest.equals(profile.digest())) {
            VerificationReport changed = new VerificationReport(reportId, VerificationReport.Status.NEEDS_HUMAN,
                    target.toString(), profile.digest(), "", List.of(),
                    List.of("trusted verification profile changed after run start"), artifactRef, Instant.now());
            runtime.saveVerificationReport(runId, changed);
            return changed;
        }
        List<VerificationCheckResult> checks = new ArrayList<>();
        Set<String> requiredIds = new LinkedHashSet<>();
        List<String> criteria = new ArrayList<>();
        if (plan != null) plan.tasks().forEach(task -> {
            requiredIds.addAll(task.requiredCheckIds()); criteria.addAll(task.acceptanceCriteria());
        });

        ExecutionResult diff = execute(target, "git diff --check", 120);
        checks.add(store(reportId, VerificationCheckResult.Stage.DIFF, "diff-check", "git diff --check",
                diff, List.of()));
        ExecutionResult names = execute(target, "git diff --name-status HEAD", 120);
        checks.add(store(reportId, VerificationCheckResult.Stage.DIFF, "name-status",
                "git diff --name-status HEAD", names, List.of()));
        ExecutionResult binaryDiff = execute(target, "git diff --binary HEAD", 120);
        String diffText = binaryDiff.stdout();
        String diffDigest = VerificationProfile.sha256(diffText.getBytes(StandardCharsets.UTF_8));
        boolean blocked = failed(diff) || failed(names) || failed(binaryDiff);

        Map<String, VerificationProfile.Check> byId = new LinkedHashMap<>();
        profile.checks().forEach(check -> byId.put(check.id(), check));
        List<VerificationProfile.Check> selected = profile.checks().stream()
                .filter(check -> check.stage() != VerificationCheckResult.Stage.ACCEPTANCE
                        || requiredIds.contains(check.id())).toList();
        for (VerificationProfile.Check check : selected) {
            if (blocked) {
                checks.add(skipped(check, "prerequisite verification stage failed"));
                continue;
            }
            ExecutionResult execution = execute(target, check.command(), check.timeoutSeconds());
            checks.add(store(reportId, check.stage(), check.id(), check.command(), execution,
                    relatedTasks(plan, check.id())));
            if (check.required() && failed(execution)) blocked = true;
        }

        List<String> unproven = new ArrayList<>();
        for (String id : requiredIds) if (!byId.containsKey(id)) unproven.add("unknown check: " + id);
        if (plan != null) plan.tasks().stream()
                .filter(task -> !task.acceptanceCriteria().isEmpty() && task.requiredCheckIds().isEmpty())
                .forEach(task -> task.acceptanceCriteria().forEach(criterion ->
                        unproven.add(task.taskId() + ": " + criterion)));
        if (profile.checks().stream().noneMatch(check -> check.stage() == VerificationCheckResult.Stage.COMPILE))
            unproven.add("compile check is not configured or detectable");
        if (profile.checks().stream().noneMatch(check -> check.stage() == VerificationCheckResult.Stage.TEST))
            unproven.add("test check is not configured or detectable");
        if (results != null) results.stream().filter(result -> result.status() == ricbot.domain.task.TaskStatus.FAILED)
                .forEach(result -> checks.add(new VerificationCheckResult(VerificationCheckResult.Stage.ACCEPTANCE,
                        "worker-" + result.taskId(), "", VerificationCheckResult.Status.FAIL, null, false, 0,
                        "scheduler", false, "worker failed: " + result.summary(),
                        result.artifacts().getOrDefault("report", ""),
                        List.of(result.taskId()), Instant.now())));
        if (names.stdout().lines().anyMatch(line -> line.startsWith("D") && isTestPath(line))) {
            unproven.add("test deletion requires human review");
        }
        if (names.stdout().lines().anyMatch(WorkspaceVerificationService::isHighRiskPath)) {
            unproven.add("high-risk build or automation diff requires human review");
        }

        Set<String> optional = profile.checks().stream().filter(check -> !check.required())
                .map(VerificationProfile.Check::id).collect(java.util.stream.Collectors.toSet());
        VerificationReport.Status status = checks.stream().anyMatch(check ->
                check.status() == VerificationCheckResult.Status.FAIL && !optional.contains(check.checkId()))
                ? VerificationReport.Status.REJECT
                : !unproven.isEmpty() || checks.stream().anyMatch(check -> check.status() == VerificationCheckResult.Status.NEEDS_HUMAN)
                    ? VerificationReport.Status.NEEDS_HUMAN : VerificationReport.Status.PASS;
        VerificationReport report = new VerificationReport(reportId, status, target.toString(), profile.digest(),
                diffDigest, checks, unproven, artifactRef, Instant.now());
        runtime.saveVerificationReport(runId, report);
        return report;
    }

    private VerificationCheckResult store(String reportId, VerificationCheckResult.Stage stage,
                                          String id, String command, ExecutionResult result, List<String> taskIds) {
        try {
            String summary = bounded((result.stdout() + "\n" + result.stderr()).trim(), 2000);
            VerificationCheckResult.Status status = unavailable(result)
                    ? VerificationCheckResult.Status.NEEDS_HUMAN
                    : failed(result) ? VerificationCheckResult.Status.FAIL : VerificationCheckResult.Status.PASS;
            return new VerificationCheckResult(stage, id,
                    VerificationProfile.sha256(command.getBytes(StandardCharsets.UTF_8)),
                    status,
                    result.exitCode(), result.timedOut(), result.duration().toMillis(), result.backend(),
                    result.truncated(), summary, "sqlite:.ricbot/runtime.db#verification/" + reportId + "/" + safe(id),
                    taskIds, Instant.now());
        } catch (Exception e) { throw new IllegalStateException("cannot persist verification check " + id, e); }
    }

    private ExecutionResult execute(Path target, String command, int seconds) {
        try {
            return backend.execute(new ExecutionRequest(command, target, environment(), Duration.ofSeconds(seconds), 256 * 1024));
        } catch (Exception e) {
            return new ExecutionResult(-1, "", e.getMessage(), false, false, Duration.ZERO, "unavailable", Map.of());
        }
    }
    private static VerificationCheckResult skipped(VerificationProfile.Check check, String reason) {
        return new VerificationCheckResult(check.stage(), check.id(),
                VerificationProfile.sha256(check.command().getBytes(StandardCharsets.UTF_8)),
                VerificationCheckResult.Status.SKIPPED, null, false, 0, "", false, reason, "", List.of(), Instant.now());
    }
    private static List<String> relatedTasks(TeamPlan plan, String checkId) {
        if (plan == null) return List.of();
        return plan.tasks().stream().filter(task -> task.requiredCheckIds().contains(checkId)).map(task -> task.taskId()).toList();
    }
    private static Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : List.of("PATH", "HOME", "USER", "LANG", "JAVA_HOME")) {
            String value = System.getenv(key); if (value != null) env.put(key, value);
        }
        return Map.copyOf(env);
    }
    private static boolean failed(ExecutionResult result) { return result.timedOut() || result.exitCode() != 0; }
    private static boolean unavailable(ExecutionResult result) {
        return result.exitCode() == -1 || "unavailable".equalsIgnoreCase(result.backend());
    }
    private static boolean isTestPath(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/tests/") || lower.endsWith("test.java")
                || lower.endsWith("test.ts") || lower.endsWith("test.js");
    }
    private static boolean isHighRiskPath(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(".github/workflows/") || lower.contains("dockerfile")
                || lower.contains("pom.xml") || lower.contains("build.gradle")
                || lower.contains("package-lock.json") || lower.contains("pnpm-lock.yaml");
    }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String bounded(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
