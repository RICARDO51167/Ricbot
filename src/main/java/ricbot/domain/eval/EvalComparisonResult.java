package ricbot.domain.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EvalComparisonResult {
    private String status;
    private String baselineRunDir;
    private String candidateRunDir;
    private int baselineTotal;
    private int candidateTotal;
    private int regressions;
    private int improvements;
    private int missingCases;
    private int newCases;
    private Map<String, Object> summaryDelta;
    private List<Map<String, Object>> changes = new ArrayList<>();
    private String artifactDir;

    public String getStatus() {
        return status;
    }

    public EvalComparisonResult setStatus(String status) {
        this.status = status;
        return this;
    }

    public String getBaselineRunDir() {
        return baselineRunDir;
    }

    public EvalComparisonResult setBaselineRunDir(String baselineRunDir) {
        this.baselineRunDir = baselineRunDir;
        return this;
    }

    public String getCandidateRunDir() {
        return candidateRunDir;
    }

    public EvalComparisonResult setCandidateRunDir(String candidateRunDir) {
        this.candidateRunDir = candidateRunDir;
        return this;
    }

    public int getBaselineTotal() {
        return baselineTotal;
    }

    public EvalComparisonResult setBaselineTotal(int baselineTotal) {
        this.baselineTotal = baselineTotal;
        return this;
    }

    public int getCandidateTotal() {
        return candidateTotal;
    }

    public EvalComparisonResult setCandidateTotal(int candidateTotal) {
        this.candidateTotal = candidateTotal;
        return this;
    }

    public int getRegressions() {
        return regressions;
    }

    public EvalComparisonResult setRegressions(int regressions) {
        this.regressions = regressions;
        return this;
    }

    public int getImprovements() {
        return improvements;
    }

    public EvalComparisonResult setImprovements(int improvements) {
        this.improvements = improvements;
        return this;
    }

    public int getMissingCases() {
        return missingCases;
    }

    public EvalComparisonResult setMissingCases(int missingCases) {
        this.missingCases = missingCases;
        return this;
    }

    public int getNewCases() {
        return newCases;
    }

    public EvalComparisonResult setNewCases(int newCases) {
        this.newCases = newCases;
        return this;
    }

    public Map<String, Object> getSummaryDelta() {
        return summaryDelta;
    }

    public EvalComparisonResult setSummaryDelta(Map<String, Object> summaryDelta) {
        this.summaryDelta = summaryDelta;
        return this;
    }

    public List<Map<String, Object>> getChanges() {
        return changes;
    }

    public EvalComparisonResult setChanges(List<Map<String, Object>> changes) {
        this.changes = changes != null ? changes : new ArrayList<>();
        return this;
    }

    public String getArtifactDir() {
        return artifactDir;
    }

    public EvalComparisonResult setArtifactDir(String artifactDir) {
        this.artifactDir = artifactDir;
        return this;
    }
}
