package ricbot.domain.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import ricbot.domain.agent.AgentLoop;
import ricbot.integration.llm.api.LLMProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class EvalReplayRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ricbot.infra.config.Config config;
    private final Function<LLMProvider, AgentLoop> agentLoopFactory;

    public EvalReplayRunner(ricbot.infra.config.Config config, Function<LLMProvider, AgentLoop> agentLoopFactory) {
        this.config = config;
        this.agentLoopFactory = agentLoopFactory;
    }

    public EvalRunSummary replay(List<Path> caseArtifacts, EvalOptions options) throws Exception {
        if (caseArtifacts == null || caseArtifacts.isEmpty()) {
            throw new IllegalArgumentException("at least one case artifact is required");
        }
        Instant started = Instant.now();
        String runId = "replay-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(started) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path artifactDir = resolveArtifactDir(options, runId);
        Path casesDir = artifactDir.resolve("cases");
        Files.createDirectories(casesDir);

        List<EvalCaseResult> results = new ArrayList<>();
        Path casesJsonl = artifactDir.resolve("cases.jsonl");
        try (var writer = Files.newBufferedWriter(casesJsonl, StandardCharsets.UTF_8)) {
            for (Path caseArtifact : caseArtifacts) {
                EvalCaseResult result = replayCase(caseArtifact, options, runId, casesDir);
                results.add(result);
                writer.write(MAPPER.writeValueAsString(result));
                writer.newLine();
                if (options != null && options.isFailFast() && isFailFastStop(result)) {
                    break;
                }
            }
        }

        Instant ended = Instant.now();
        EvalRunSummary summary = summarize(runId, started, ended, artifactDir, results);
        writeJson(artifactDir.resolve("summary.json"), summary);
        EvalReportWriter.write(artifactDir, summary, results, true);
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

    public static List<Path> resolveCaseArtifacts(Path casePath, Path runDir, int limit) throws Exception {
        List<Path> paths = new ArrayList<>();
        if (casePath != null) {
            paths.add(casePath.toAbsolutePath().normalize());
            return paths;
        }
        if (runDir == null) {
            throw new IllegalArgumentException("--case or --run is required");
        }
        Path casesDir = runDir.toAbsolutePath().normalize().resolve("cases");
        try (var stream = Files.list(casesDir)) {
            paths.addAll(stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList());
        }
        if (limit > 0 && paths.size() > limit) {
            return new ArrayList<>(paths.subList(0, limit));
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private EvalCaseResult replayCase(Path caseArtifact, EvalOptions options, String runId, Path casesDir) throws Exception {
        Map<String, Object> artifact = MAPPER.readValue(caseArtifact.toFile(), MAP_TYPE);
        EvalScenario scenario = MAPPER.convertValue(artifact.get("scenario"), EvalScenario.class);
        Map<String, Object> originalResult = artifact.get("result") instanceof Map<?, ?> raw
                ? copyMap(raw)
                : Map.of();
        List<Map<String, Object>> modelCalls = originalResult.get("model_calls") instanceof List<?> rawCalls
                ? rawCalls.stream()
                .filter(Map.class::isInstance)
                .map(item -> copyMap((Map<?, ?>) item))
                .toList()
                : List.of();
        if (modelCalls.isEmpty() && !Boolean.TRUE.equals(scenario.getSkip())) {
            EvalCaseResult invalid = new EvalCaseResult()
                    .setId(scenario.getId())
                    .setSessionKey("replay:" + runId + ":" + scenario.getId())
                    .setStatus("fail")
                    .setFailureKind("replay_artifact_invalid")
                    .setFailureDetail("case artifact has no model_calls");
            Path out = casesDir.resolve(safeFileName(scenario.getId()) + ".json");
            invalid.setArtifactPath(out.toAbsolutePath().normalize().toString());
            writeCase(out, scenario, invalid);
            return invalid;
        }

        String originalResponse = stringValue(originalResult.get("response"));
        EvalReplayProvider replayProvider = new EvalReplayProvider(modelCalls);
        EvalRecordingProvider recorder = new EvalRecordingProvider(replayProvider);
        AgentLoop loop = agentLoopFactory.apply(recorder);
        try {
            EvalOptions replayOptions = new EvalOptions()
                    .setOutputDir(options != null ? options.getOutputDir() : null)
                    .setSessionPrefix("replay:" + runId)
                    .setFailFast(options != null && options.isFailFast())
                    .setAllowUnsafeWorkspaceClean(options != null && options.isAllowUnsafeWorkspaceClean());
            EvalCaseResult result = new EvalHarness(loop, config, recorder).runScenario(
                    scenario,
                    replayOptions,
                    runId,
                    casesDir
            );
            List<String> replayErrors = new ArrayList<>();
            if (!java.util.Objects.equals(originalResponse, result.getResponse())) {
                replayErrors.add("response mismatch");
            }
            if (replayProvider.consumedCalls() != replayProvider.totalCalls()) {
                replayErrors.add("recorded model calls not fully consumed: consumed="
                        + replayProvider.consumedCalls() + ", total=" + replayProvider.totalCalls());
            }
            replayErrors.addAll(replayProvider.requestMismatches());
            if (!replayErrors.isEmpty()) {
                result.setReplayErrors(replayErrors)
                        .setStatus("fail")
                        .setFailureKind("replay_mismatch")
                        .setFailureDetail(replayErrors.get(0));
            }
            writeCase(Path.of(result.getArtifactPath()), scenario, result);
            return result;
        } finally {
            loop.stop();
        }
    }

    private EvalRunSummary summarize(
            String runId,
            Instant started,
            Instant ended,
            Path artifactDir,
            List<EvalCaseResult> results
    ) {
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
                    failuresByKind.merge(result.getFailureKind() != null ? result.getFailureKind() : "unknown", 1, Integer::sum);
                }
            }
        }
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

    private Path resolveArtifactDir(EvalOptions options, String runId) {
        Path outputDir = options != null ? options.getOutputDir() : null;
        if (outputDir == null) {
            outputDir = config.getWorkspacePath().resolve(".ricbot").resolve("eval-replays");
        }
        return outputDir.toAbsolutePath().normalize().resolve(runId);
    }

    private static void writeCase(Path path, EvalScenario scenario, EvalCaseResult result) throws Exception {
        Map<String, Object> caseArtifact = new LinkedHashMap<>();
        caseArtifact.put("scenario", MAPPER.convertValue(scenario, MAP_TYPE));
        caseArtifact.put("result", MAPPER.convertValue(result, MAP_TYPE));
        writeJson(path, caseArtifact);
    }

    private static void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
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

    private static String stringValue(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }

    private static String safeFileName(String value) {
        String raw = value != null && !value.isBlank() ? value : "case";
        return raw.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
