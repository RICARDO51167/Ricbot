package ricbot.domain.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class EvalRunsViewerService {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int DEFAULT_LIMIT = 20;

    private final Path workspace;
    private final Path evalRoot;

    public EvalRunsViewerService(Path workspace) {
        this.workspace = (workspace != null ? workspace : Path.of(".")).toAbsolutePath().normalize();
        this.evalRoot = this.workspace.resolve(".ricbot").resolve("evals").toAbsolutePath().normalize();
    }

    public List<EvalRunSummary> listRuns() {
        return listRuns(DEFAULT_LIMIT);
    }

    public List<EvalRunSummary> listRuns(int limit) {
        if (!Files.isDirectory(evalRoot)) {
            return List.of();
        }
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;
        try (Stream<Path> children = Files.list(evalRoot)) {
            return children
                    .filter(Files::isDirectory)
                    .map(this::readSummaryForList)
                    .sorted(Comparator.comparing(this::sortKey, Comparator.reverseOrder()))
                    .limit(effectiveLimit)
                    .toList();
        } catch (Exception e) {
            EvalRunSummary warning = new EvalRunSummary()
                    .setRunId("")
                    .setWarnings(List.of("failed to list eval runs: " + message(e)));
            return List.of(warning);
        }
    }

    public EvalRunDetail detail(String runId) {
        Path runDir = resolveRunDir(runId);
        if (!Files.isDirectory(runDir)) {
            throw new EvalRunNotFoundException("eval run not found: " + runId);
        }

        List<String> warnings = new ArrayList<>();
        EvalRunSummary summary = readSummary(runDir, warnings);
        Map<String, Object> manifest = readManifest(runDir.resolve("manifest.json"), warnings);
        applyManifest(summary, manifest);
        applyViewerFields(summary, runDir, warnings);

        List<EvalCaseSummary> cases = readCases(runDir, warnings);
        String reportMarkdown = readReport(runDir.resolve("report.md"), warnings);
        List<String> combinedWarnings = new ArrayList<>(warnings);
        return new EvalRunDetail(summary, cases, reportMarkdown, manifest, combinedWarnings);
    }

    public Path evalRoot() {
        return evalRoot;
    }

    private EvalRunSummary readSummaryForList(Path runDir) {
        List<String> warnings = new ArrayList<>();
        EvalRunSummary summary = readSummary(runDir, warnings);
        Map<String, Object> manifest = readManifest(runDir.resolve("manifest.json"), warnings);
        applyManifest(summary, manifest);
        applyViewerFields(summary, runDir, warnings);
        return summary;
    }

    private EvalRunSummary readSummary(Path runDir, List<String> warnings) {
        Path summaryPath = runDir.resolve("summary.json");
        if (!Files.isRegularFile(summaryPath)) {
            warnings.add("missing summary.json");
            return fallbackSummary(runDir);
        }
        try {
            EvalRunSummary summary = MAPPER.readValue(summaryPath.toFile(), EvalRunSummary.class);
            if (summary.getRunId() == null || summary.getRunId().isBlank()) {
                summary.setRunId(runDir.getFileName().toString());
            }
            return summary;
        } catch (Exception e) {
            warnings.add("failed to read summary.json: " + message(e));
            return fallbackSummary(runDir);
        }
    }

    private Map<String, Object> readManifest(Path manifestPath, List<String> warnings) {
        if (!Files.isRegularFile(manifestPath)) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(manifestPath.toFile(), MAP_TYPE);
            return sanitizeMap(raw);
        } catch (Exception e) {
            warnings.add("failed to read manifest.json: " + message(e));
            return Map.of();
        }
    }

    private List<EvalCaseSummary> readCases(Path runDir, List<String> warnings) {
        Path casesPath = runDir.resolve("cases.jsonl");
        if (!Files.isRegularFile(casesPath)) {
            return List.of();
        }
        List<EvalCaseSummary> cases = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(casesPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    EvalCaseResult result = MAPPER.readValue(trimmed, EvalCaseResult.class);
                    cases.add(toCaseSummary(result, warnings));
                } catch (Exception e) {
                    warnings.add("failed to read cases.jsonl line " + lineNo + ": " + message(e));
                }
            }
        } catch (Exception e) {
            warnings.add("failed to read cases.jsonl: " + message(e));
        }
        return cases;
    }

    private String readReport(Path reportPath, List<String> warnings) {
        if (!Files.isRegularFile(reportPath)) {
            return "";
        }
        try {
            return Files.readString(reportPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            warnings.add("failed to read report.md: " + message(e));
            return "";
        }
    }

    private EvalCaseSummary toCaseSummary(EvalCaseResult result, List<String> warnings) {
        String artifactPath = safeArtifactPath(result.getArtifactPath(), warnings);
        return new EvalCaseSummary(
                result.getId(),
                result.getStatus(),
                result.getFailureKind(),
                result.getDurationMs(),
                result.getToolsUsed(),
                artifactPath
        );
    }

    private String safeArtifactPath(String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            Path path = Path.of(raw).toAbsolutePath().normalize();
            if (path.startsWith(evalRoot)) {
                return evalRoot.relativize(path).toString();
            }
        } catch (Exception ignored) {
        }
        warnings.add("case artifact path is outside eval root and was hidden");
        return "";
    }

    private void applyManifest(EvalRunSummary summary, Map<String, Object> manifest) {
        if (summary == null || manifest == null || manifest.isEmpty()) {
            return;
        }
        if (summary.getProviderMode() == null || summary.getProviderMode().isBlank()) {
            summary.setProviderMode(stringValue(manifest.get("provider_mode")));
        }
        if (summary.getModel() == null || summary.getModel().isBlank()) {
            summary.setModel(stringValue(manifest.get("model")));
        }
    }

    private void applyViewerFields(EvalRunSummary summary, Path runDir, List<String> warnings) {
        String reportPath = "";
        Path report = runDir.resolve("report.md").toAbsolutePath().normalize();
        if (report.startsWith(evalRoot) && Files.isRegularFile(report)) {
            reportPath = evalRoot.relativize(report).toString();
        }
        summary.setReportPath(reportPath);
        summary.setWarnings(warnings);
    }

    private EvalRunSummary fallbackSummary(Path runDir) {
        EvalRunSummary summary = new EvalRunSummary();
        summary.setRunId(runDir.getFileName() != null ? runDir.getFileName().toString() : "");
        try {
            summary.setStartedAt(Files.getLastModifiedTime(runDir).toInstant().toString());
        } catch (Exception ignored) {
            summary.setStartedAt("");
        }
        return summary;
    }

    private Path resolveRunDir(String runId) {
        if (!isSafeRunId(runId)) {
            throw new IllegalArgumentException("invalid eval run id");
        }
        Path runDir = evalRoot.resolve(runId).toAbsolutePath().normalize();
        if (!runDir.startsWith(evalRoot) || !evalRoot.equals(runDir.getParent())) {
            throw new IllegalArgumentException("invalid eval run id");
        }
        return runDir;
    }

    private boolean isSafeRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        String trimmed = runId.trim();
        return SAFE_RUN_ID.matcher(trimmed).matches()
                && !trimmed.startsWith(".")
                && !trimmed.contains("..")
                && !trimmed.contains("/")
                && !trimmed.contains("\\");
    }

    private String sortKey(EvalRunSummary summary) {
        String createdAt = summary.getCreatedAt();
        if (createdAt != null && !createdAt.isBlank()) {
            return createdAt;
        }
        return summary.getRunId() != null ? summary.getRunId() : "";
    }

    private Map<String, Object> sanitizeMap(Map<?, ?> raw) {
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

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("apikey")
                || normalized.equals("authorization")
                || normalized.contains("secret")
                || normalized.equals("password")
                || normalized.endsWith("password")
                || normalized.equals("token")
                || normalized.endsWith("token");
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    public static class EvalRunNotFoundException extends RuntimeException {
        public EvalRunNotFoundException(String message) {
            super(message);
        }
    }
}
