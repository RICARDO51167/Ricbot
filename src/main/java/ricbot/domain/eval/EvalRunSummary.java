package ricbot.domain.eval;

import java.util.LinkedHashMap;
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
}
