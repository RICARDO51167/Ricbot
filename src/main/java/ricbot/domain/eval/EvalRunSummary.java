package ricbot.domain.eval;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvalRunSummary {
    private String runId;
    private String startedAt;
    private String endedAt;
    private int total;
    private int passed;
    private int failed;
    private int skipped;
    private int expectedFailed;
    private int unexpectedPassed;
    private long durationMs;
    private long durationP50Ms;
    private long durationP95Ms;
    private int totalModelCalls;
    private int totalToolCalls;
    private int totalWorkspaceChanges;
    private Map<String, Integer> totalUsage = new LinkedHashMap<>();
    private Map<String, Integer> failuresByKind = new LinkedHashMap<>();
    private String artifactDir;
    private String providerMode;
    private String model;
    private String reportPath;
    private List<String> warnings = new ArrayList<>();

    public String getRunId() {
        return runId;
    }

    public EvalRunSummary setRunId(String runId) {
        this.runId = runId;
        return this;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public EvalRunSummary setStartedAt(String startedAt) {
        this.startedAt = startedAt;
        return this;
    }

    public String getEndedAt() {
        return endedAt;
    }

    public EvalRunSummary setEndedAt(String endedAt) {
        this.endedAt = endedAt;
        return this;
    }

    public int getTotal() {
        return total;
    }

    public EvalRunSummary setTotal(int total) {
        this.total = total;
        return this;
    }

    public int getPassed() {
        return passed;
    }

    public EvalRunSummary setPassed(int passed) {
        this.passed = passed;
        return this;
    }

    public int getFailed() {
        return failed;
    }

    public EvalRunSummary setFailed(int failed) {
        this.failed = failed;
        return this;
    }

    public int getSkipped() {
        return skipped;
    }

    public EvalRunSummary setSkipped(int skipped) {
        this.skipped = skipped;
        return this;
    }

    public int getExpectedFailed() {
        return expectedFailed;
    }

    public EvalRunSummary setExpectedFailed(int expectedFailed) {
        this.expectedFailed = expectedFailed;
        return this;
    }

    public int getUnexpectedPassed() {
        return unexpectedPassed;
    }

    public EvalRunSummary setUnexpectedPassed(int unexpectedPassed) {
        this.unexpectedPassed = unexpectedPassed;
        return this;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public EvalRunSummary setDurationMs(long durationMs) {
        this.durationMs = durationMs;
        return this;
    }

    public long getDurationP50Ms() {
        return durationP50Ms;
    }

    public EvalRunSummary setDurationP50Ms(long durationP50Ms) {
        this.durationP50Ms = durationP50Ms;
        return this;
    }

    public long getDurationP95Ms() {
        return durationP95Ms;
    }

    public EvalRunSummary setDurationP95Ms(long durationP95Ms) {
        this.durationP95Ms = durationP95Ms;
        return this;
    }

    public int getTotalModelCalls() {
        return totalModelCalls;
    }

    public EvalRunSummary setTotalModelCalls(int totalModelCalls) {
        this.totalModelCalls = totalModelCalls;
        return this;
    }

    public int getTotalToolCalls() {
        return totalToolCalls;
    }

    public EvalRunSummary setTotalToolCalls(int totalToolCalls) {
        this.totalToolCalls = totalToolCalls;
        return this;
    }

    public int getTotalWorkspaceChanges() {
        return totalWorkspaceChanges;
    }

    public EvalRunSummary setTotalWorkspaceChanges(int totalWorkspaceChanges) {
        this.totalWorkspaceChanges = totalWorkspaceChanges;
        return this;
    }

    public Map<String, Integer> getTotalUsage() {
        return totalUsage;
    }

    public EvalRunSummary setTotalUsage(Map<String, Integer> totalUsage) {
        this.totalUsage = totalUsage != null ? totalUsage : new LinkedHashMap<>();
        return this;
    }

    public Map<String, Integer> getFailuresByKind() {
        return failuresByKind;
    }

    public EvalRunSummary setFailuresByKind(Map<String, Integer> failuresByKind) {
        this.failuresByKind = failuresByKind != null ? failuresByKind : new LinkedHashMap<>();
        return this;
    }

    public String getArtifactDir() {
        return artifactDir;
    }

    public EvalRunSummary setArtifactDir(String artifactDir) {
        this.artifactDir = artifactDir;
        return this;
    }

    @JsonIgnore
    public String getCreatedAt() {
        return startedAt;
    }

    @JsonIgnore
    public int getXpass() {
        return unexpectedPassed;
    }

    @JsonIgnore
    public String getProviderMode() {
        return providerMode;
    }

    public EvalRunSummary setProviderMode(String providerMode) {
        this.providerMode = providerMode;
        return this;
    }

    @JsonIgnore
    public String getModel() {
        return model;
    }

    public EvalRunSummary setModel(String model) {
        this.model = model;
        return this;
    }

    @JsonIgnore
    public String getReportPath() {
        return reportPath;
    }

    public EvalRunSummary setReportPath(String reportPath) {
        this.reportPath = reportPath;
        return this;
    }

    @JsonIgnore
    public List<String> getWarnings() {
        return warnings;
    }

    public EvalRunSummary setWarnings(List<String> warnings) {
        this.warnings = warnings != null ? new ArrayList<>(warnings) : new ArrayList<>();
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("createdAt", getCreatedAt());
        out.put("startedAt", startedAt);
        out.put("endedAt", endedAt);
        out.put("providerMode", providerMode);
        out.put("model", model);
        out.put("total", total);
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("skipped", skipped);
        out.put("expectedFailed", expectedFailed);
        out.put("xpass", getXpass());
        out.put("unexpectedPassed", unexpectedPassed);
        out.put("durationMs", durationMs);
        out.put("durationP50Ms", durationP50Ms);
        out.put("durationP95Ms", durationP95Ms);
        out.put("totalModelCalls", totalModelCalls);
        out.put("totalToolCalls", totalToolCalls);
        out.put("totalWorkspaceChanges", totalWorkspaceChanges);
        out.put("totalUsage", totalUsage);
        out.put("failuresByKind", failuresByKind);
        out.put("artifactDir", artifactDir);
        out.put("reportPath", reportPath);
        out.put("warnings", warnings);
        return out;
    }
}
