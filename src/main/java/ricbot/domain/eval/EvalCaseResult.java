package ricbot.domain.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EvalCaseResult {
    private String id;
    private String sessionKey;
    private String status;
    private String failureKind;
    private String failureDetail;
    private String expectationStatus;
    private String expectationReason;
    private String response;
    private List<Map<String, Object>> turnResults = new ArrayList<>();
    private long durationMs;
    private List<String> toolsUsed = new ArrayList<>();
    private Map<String, Object> runTrace;
    private Map<String, Object> contextTrace;
    private List<Map<String, Object>> modelCalls = new ArrayList<>();
    private Map<String, Object> workspaceDiff;
    private Map<String, Object> sessionState;
    private Map<String, Object> memoryState;
    private List<String> sideEffectViolations = new ArrayList<>();
    private List<String> replayErrors = new ArrayList<>();
    private List<String> assertionErrors = new ArrayList<>();
    private List<String> fixtureErrors = new ArrayList<>();
    private List<String> workspaceRestoreErrors = new ArrayList<>();
    private List<String> sessionRestoreErrors = new ArrayList<>();
    private String artifactPath;
    private String maxDurationExceededDetail;

    public String getId() {
        return id;
    }

    public EvalCaseResult setId(String id) {
        this.id = id;
        return this;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public EvalCaseResult setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
        return this;
    }

    public String getStatus() {
        return status;
    }

    public EvalCaseResult setStatus(String status) {
        this.status = status;
        return this;
    }

    public String getFailureKind() {
        return failureKind;
    }

    public EvalCaseResult setFailureKind(String failureKind) {
        this.failureKind = failureKind;
        return this;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public EvalCaseResult setFailureDetail(String failureDetail) {
        this.failureDetail = failureDetail;
        return this;
    }

    public String getExpectationStatus() {
        return expectationStatus;
    }

    public EvalCaseResult setExpectationStatus(String expectationStatus) {
        this.expectationStatus = expectationStatus;
        return this;
    }

    public String getExpectationReason() {
        return expectationReason;
    }

    public EvalCaseResult setExpectationReason(String expectationReason) {
        this.expectationReason = expectationReason;
        return this;
    }

    public String getResponse() {
        return response;
    }

    public EvalCaseResult setResponse(String response) {
        this.response = response;
        return this;
    }

    public List<Map<String, Object>> getTurnResults() {
        return turnResults;
    }

    public EvalCaseResult setTurnResults(List<Map<String, Object>> turnResults) {
        this.turnResults = turnResults != null ? turnResults : new ArrayList<>();
        return this;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public EvalCaseResult setDurationMs(long durationMs) {
        this.durationMs = durationMs;
        return this;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public EvalCaseResult setToolsUsed(List<String> toolsUsed) {
        this.toolsUsed = toolsUsed != null ? toolsUsed : new ArrayList<>();
        return this;
    }

    public Map<String, Object> getRunTrace() {
        return runTrace;
    }

    public EvalCaseResult setRunTrace(Map<String, Object> runTrace) {
        this.runTrace = runTrace;
        return this;
    }

    public Map<String, Object> getContextTrace() {
        return contextTrace;
    }

    public EvalCaseResult setContextTrace(Map<String, Object> contextTrace) {
        this.contextTrace = contextTrace;
        return this;
    }

    public List<Map<String, Object>> getModelCalls() {
        return modelCalls;
    }

    public EvalCaseResult setModelCalls(List<Map<String, Object>> modelCalls) {
        this.modelCalls = modelCalls != null ? modelCalls : new ArrayList<>();
        return this;
    }

    public Map<String, Object> getWorkspaceDiff() {
        return workspaceDiff;
    }

    public EvalCaseResult setWorkspaceDiff(Map<String, Object> workspaceDiff) {
        this.workspaceDiff = workspaceDiff;
        return this;
    }

    public Map<String, Object> getSessionState() {
        return sessionState;
    }

    public EvalCaseResult setSessionState(Map<String, Object> sessionState) {
        this.sessionState = sessionState;
        return this;
    }

    public Map<String, Object> getMemoryState() {
        return memoryState;
    }

    public EvalCaseResult setMemoryState(Map<String, Object> memoryState) {
        this.memoryState = memoryState;
        return this;
    }

    public List<String> getSideEffectViolations() {
        return sideEffectViolations;
    }

    public EvalCaseResult setSideEffectViolations(List<String> sideEffectViolations) {
        this.sideEffectViolations = sideEffectViolations != null ? sideEffectViolations : new ArrayList<>();
        return this;
    }

    public List<String> getReplayErrors() {
        return replayErrors;
    }

    public EvalCaseResult setReplayErrors(List<String> replayErrors) {
        this.replayErrors = replayErrors != null ? replayErrors : new ArrayList<>();
        return this;
    }

    public List<String> getAssertionErrors() {
        return assertionErrors;
    }

    public EvalCaseResult setAssertionErrors(List<String> assertionErrors) {
        this.assertionErrors = assertionErrors != null ? assertionErrors : new ArrayList<>();
        return this;
    }

    public List<String> getFixtureErrors() {
        return fixtureErrors;
    }

    public EvalCaseResult setFixtureErrors(List<String> fixtureErrors) {
        this.fixtureErrors = fixtureErrors != null ? fixtureErrors : new ArrayList<>();
        return this;
    }

    public List<String> getWorkspaceRestoreErrors() {
        return workspaceRestoreErrors;
    }

    public EvalCaseResult setWorkspaceRestoreErrors(List<String> workspaceRestoreErrors) {
        this.workspaceRestoreErrors = workspaceRestoreErrors != null ? workspaceRestoreErrors : new ArrayList<>();
        return this;
    }

    public List<String> getSessionRestoreErrors() {
        return sessionRestoreErrors;
    }

    public EvalCaseResult setSessionRestoreErrors(List<String> sessionRestoreErrors) {
        this.sessionRestoreErrors = sessionRestoreErrors != null ? sessionRestoreErrors : new ArrayList<>();
        return this;
    }

    public String getArtifactPath() {
        return artifactPath;
    }

    public EvalCaseResult setArtifactPath(String artifactPath) {
        this.artifactPath = artifactPath;
        return this;
    }

    public String getMaxDurationExceededDetail() {
        return maxDurationExceededDetail;
    }

    public EvalCaseResult setMaxDurationExceededDetail(String maxDurationExceededDetail) {
        this.maxDurationExceededDetail = maxDurationExceededDetail;
        return this;
    }
}
