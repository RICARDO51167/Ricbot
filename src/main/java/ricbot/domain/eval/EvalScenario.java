package ricbot.domain.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EvalScenario {
    private String id;
    private String input;
    private List<EvalTurn> turns = new ArrayList<>();
    private String session;
    private List<String> expectedContains = new ArrayList<>();
    private List<String> expectedNotContains = new ArrayList<>();
    private List<String> expectedRegex = new ArrayList<>();
    private List<String> expectedJsonRequired = new ArrayList<>();
    private List<String> expectedJsonAbsent = new ArrayList<>();
    private Map<String, Object> expectedJsonValues;
    private List<String> expectedTools = new ArrayList<>();
    private List<String> forbiddenTools = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private Boolean skip;
    private String skipReason;
    private Boolean xfail;
    private String xfailReason;
    private String expectedFailureKind;
    private Boolean ignoreCase = Boolean.TRUE;
    private Integer maxDurationMs;
    private String allowedSideEffects;
    private Integer maxFileChanges;
    private Integer maxModelCalls;
    private Integer maxToolCalls;
    private String expectedStopReason;
    private Map<String, Object> metadata;
    private Boolean cleanWorkspace;
    private Boolean restoreWorkspace;
    private Map<String, String> workspaceFiles;
    private Map<String, List<String>> expectedFileContains;
    private Map<String, List<String>> expectedFileNotContains;
    private Boolean restoreSession;
    private Integer expectedSessionMessageCount;
    private List<String> expectedSessionContains = new ArrayList<>();
    private List<String> expectedSessionNotContains = new ArrayList<>();
    private Map<String, Integer> expectedSessionRoleCounts;
    private Map<String, Integer> expectedMemoryCounts;
    private Map<String, List<String>> expectedMemoryFileContains;
    private Map<String, List<String>> expectedMemoryFileNotContains;

    public String getId() {
        return id;
    }

    public EvalScenario setId(String id) {
        this.id = id;
        return this;
    }

    public String getInput() {
        return input;
    }

    public EvalScenario setInput(String input) {
        this.input = input;
        return this;
    }

    public List<EvalTurn> getTurns() {
        return turns;
    }

    public EvalScenario setTurns(List<EvalTurn> turns) {
        this.turns = turns != null ? turns : new ArrayList<>();
        return this;
    }

    public String getSession() {
        return session;
    }

    public EvalScenario setSession(String session) {
        this.session = session;
        return this;
    }

    public List<String> getExpectedContains() {
        return expectedContains;
    }

    public EvalScenario setExpectedContains(List<String> expectedContains) {
        this.expectedContains = expectedContains != null ? expectedContains : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedNotContains() {
        return expectedNotContains;
    }

    public EvalScenario setExpectedNotContains(List<String> expectedNotContains) {
        this.expectedNotContains = expectedNotContains != null ? expectedNotContains : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedRegex() {
        return expectedRegex;
    }

    public EvalScenario setExpectedRegex(List<String> expectedRegex) {
        this.expectedRegex = expectedRegex != null ? expectedRegex : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedJsonRequired() {
        return expectedJsonRequired;
    }

    public EvalScenario setExpectedJsonRequired(List<String> expectedJsonRequired) {
        this.expectedJsonRequired = expectedJsonRequired != null ? expectedJsonRequired : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedJsonAbsent() {
        return expectedJsonAbsent;
    }

    public EvalScenario setExpectedJsonAbsent(List<String> expectedJsonAbsent) {
        this.expectedJsonAbsent = expectedJsonAbsent != null ? expectedJsonAbsent : new ArrayList<>();
        return this;
    }

    public Map<String, Object> getExpectedJsonValues() {
        return expectedJsonValues;
    }

    public EvalScenario setExpectedJsonValues(Map<String, Object> expectedJsonValues) {
        this.expectedJsonValues = expectedJsonValues;
        return this;
    }

    public List<String> getExpectedTools() {
        return expectedTools;
    }

    public EvalScenario setExpectedTools(List<String> expectedTools) {
        this.expectedTools = expectedTools != null ? expectedTools : new ArrayList<>();
        return this;
    }

    public List<String> getForbiddenTools() {
        return forbiddenTools;
    }

    public EvalScenario setForbiddenTools(List<String> forbiddenTools) {
        this.forbiddenTools = forbiddenTools != null ? forbiddenTools : new ArrayList<>();
        return this;
    }

    public List<String> getTags() {
        return tags;
    }

    public EvalScenario setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
        return this;
    }

    public Boolean getSkip() {
        return skip;
    }

    public EvalScenario setSkip(Boolean skip) {
        this.skip = skip;
        return this;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public EvalScenario setSkipReason(String skipReason) {
        this.skipReason = skipReason;
        return this;
    }

    public Boolean getXfail() {
        return xfail;
    }

    public EvalScenario setXfail(Boolean xfail) {
        this.xfail = xfail;
        return this;
    }

    public String getXfailReason() {
        return xfailReason;
    }

    public EvalScenario setXfailReason(String xfailReason) {
        this.xfailReason = xfailReason;
        return this;
    }

    public String getExpectedFailureKind() {
        return expectedFailureKind;
    }

    public EvalScenario setExpectedFailureKind(String expectedFailureKind) {
        this.expectedFailureKind = expectedFailureKind;
        return this;
    }

    public Boolean getIgnoreCase() {
        return ignoreCase;
    }

    public EvalScenario setIgnoreCase(Boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
        return this;
    }

    public Integer getMaxDurationMs() {
        return maxDurationMs;
    }

    public EvalScenario setMaxDurationMs(Integer maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
        return this;
    }

    public String getAllowedSideEffects() {
        return allowedSideEffects;
    }

    public EvalScenario setAllowedSideEffects(String allowedSideEffects) {
        this.allowedSideEffects = allowedSideEffects;
        return this;
    }

    public Integer getMaxFileChanges() {
        return maxFileChanges;
    }

    public EvalScenario setMaxFileChanges(Integer maxFileChanges) {
        this.maxFileChanges = maxFileChanges;
        return this;
    }

    public Integer getMaxModelCalls() {
        return maxModelCalls;
    }

    public EvalScenario setMaxModelCalls(Integer maxModelCalls) {
        this.maxModelCalls = maxModelCalls;
        return this;
    }

    public Integer getMaxToolCalls() {
        return maxToolCalls;
    }

    public EvalScenario setMaxToolCalls(Integer maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
        return this;
    }

    public String getExpectedStopReason() {
        return expectedStopReason;
    }

    public EvalScenario setExpectedStopReason(String expectedStopReason) {
        this.expectedStopReason = expectedStopReason;
        return this;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public EvalScenario setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }

    public Boolean getCleanWorkspace() {
        return cleanWorkspace;
    }

    public EvalScenario setCleanWorkspace(Boolean cleanWorkspace) {
        this.cleanWorkspace = cleanWorkspace;
        return this;
    }

    public Boolean getRestoreWorkspace() {
        return restoreWorkspace;
    }

    public EvalScenario setRestoreWorkspace(Boolean restoreWorkspace) {
        this.restoreWorkspace = restoreWorkspace;
        return this;
    }

    public Map<String, String> getWorkspaceFiles() {
        return workspaceFiles;
    }

    public EvalScenario setWorkspaceFiles(Map<String, String> workspaceFiles) {
        this.workspaceFiles = workspaceFiles;
        return this;
    }

    public Map<String, List<String>> getExpectedFileContains() {
        return expectedFileContains;
    }

    public EvalScenario setExpectedFileContains(Map<String, List<String>> expectedFileContains) {
        this.expectedFileContains = expectedFileContains;
        return this;
    }

    public Map<String, List<String>> getExpectedFileNotContains() {
        return expectedFileNotContains;
    }

    public EvalScenario setExpectedFileNotContains(Map<String, List<String>> expectedFileNotContains) {
        this.expectedFileNotContains = expectedFileNotContains;
        return this;
    }

    public Boolean getRestoreSession() {
        return restoreSession;
    }

    public EvalScenario setRestoreSession(Boolean restoreSession) {
        this.restoreSession = restoreSession;
        return this;
    }

    public Integer getExpectedSessionMessageCount() {
        return expectedSessionMessageCount;
    }

    public EvalScenario setExpectedSessionMessageCount(Integer expectedSessionMessageCount) {
        this.expectedSessionMessageCount = expectedSessionMessageCount;
        return this;
    }

    public List<String> getExpectedSessionContains() {
        return expectedSessionContains;
    }

    public EvalScenario setExpectedSessionContains(List<String> expectedSessionContains) {
        this.expectedSessionContains = expectedSessionContains != null ? expectedSessionContains : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedSessionNotContains() {
        return expectedSessionNotContains;
    }

    public EvalScenario setExpectedSessionNotContains(List<String> expectedSessionNotContains) {
        this.expectedSessionNotContains = expectedSessionNotContains != null ? expectedSessionNotContains : new ArrayList<>();
        return this;
    }

    public Map<String, Integer> getExpectedSessionRoleCounts() {
        return expectedSessionRoleCounts;
    }

    public EvalScenario setExpectedSessionRoleCounts(Map<String, Integer> expectedSessionRoleCounts) {
        this.expectedSessionRoleCounts = expectedSessionRoleCounts;
        return this;
    }

    public Map<String, Integer> getExpectedMemoryCounts() {
        return expectedMemoryCounts;
    }

    public EvalScenario setExpectedMemoryCounts(Map<String, Integer> expectedMemoryCounts) {
        this.expectedMemoryCounts = expectedMemoryCounts;
        return this;
    }

    public Map<String, List<String>> getExpectedMemoryFileContains() {
        return expectedMemoryFileContains;
    }

    public EvalScenario setExpectedMemoryFileContains(Map<String, List<String>> expectedMemoryFileContains) {
        this.expectedMemoryFileContains = expectedMemoryFileContains;
        return this;
    }

    public Map<String, List<String>> getExpectedMemoryFileNotContains() {
        return expectedMemoryFileNotContains;
    }

    public EvalScenario setExpectedMemoryFileNotContains(Map<String, List<String>> expectedMemoryFileNotContains) {
        this.expectedMemoryFileNotContains = expectedMemoryFileNotContains;
        return this;
    }
}
