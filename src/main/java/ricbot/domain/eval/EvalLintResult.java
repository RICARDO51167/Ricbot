package ricbot.domain.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EvalLintResult {
    private String status;
    private String scenariosPath;
    private int totalScenarios;
    private int errors;
    private int warnings;
    private List<Map<String, Object>> issues = new ArrayList<>();
    private String artifactDir;

    public String getStatus() {
        return status;
    }

    public EvalLintResult setStatus(String status) {
        this.status = status;
        return this;
    }

    public String getScenariosPath() {
        return scenariosPath;
    }

    public EvalLintResult setScenariosPath(String scenariosPath) {
        this.scenariosPath = scenariosPath;
        return this;
    }

    public int getTotalScenarios() {
        return totalScenarios;
    }

    public EvalLintResult setTotalScenarios(int totalScenarios) {
        this.totalScenarios = totalScenarios;
        return this;
    }

    public int getErrors() {
        return errors;
    }

    public EvalLintResult setErrors(int errors) {
        this.errors = errors;
        return this;
    }

    public int getWarnings() {
        return warnings;
    }

    public EvalLintResult setWarnings(int warnings) {
        this.warnings = warnings;
        return this;
    }

    public List<Map<String, Object>> getIssues() {
        return issues;
    }

    public EvalLintResult setIssues(List<Map<String, Object>> issues) {
        this.issues = issues != null ? issues : new ArrayList<>();
        return this;
    }

    public String getArtifactDir() {
        return artifactDir;
    }

    public EvalLintResult setArtifactDir(String artifactDir) {
        this.artifactDir = artifactDir;
        return this;
    }
}
