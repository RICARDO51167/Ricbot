package ricbot.domain.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EvalTurn {
    private String input;
    private List<String> expectedContains = new ArrayList<>();
    private List<String> expectedNotContains = new ArrayList<>();
    private List<String> expectedRegex = new ArrayList<>();
    private List<String> expectedJsonRequired = new ArrayList<>();
    private List<String> expectedJsonAbsent = new ArrayList<>();
    private Map<String, Object> expectedJsonValues;
    private List<String> expectedTools = new ArrayList<>();
    private List<String> forbiddenTools = new ArrayList<>();
    private String expectedStopReason;
    private Map<String, Object> metadata;

    public String getInput() {
        return input;
    }

    public EvalTurn setInput(String input) {
        this.input = input;
        return this;
    }

    public List<String> getExpectedContains() {
        return expectedContains;
    }

    public EvalTurn setExpectedContains(List<String> expectedContains) {
        this.expectedContains = expectedContains != null ? expectedContains : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedNotContains() {
        return expectedNotContains;
    }

    public EvalTurn setExpectedNotContains(List<String> expectedNotContains) {
        this.expectedNotContains = expectedNotContains != null ? expectedNotContains : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedRegex() {
        return expectedRegex;
    }

    public EvalTurn setExpectedRegex(List<String> expectedRegex) {
        this.expectedRegex = expectedRegex != null ? expectedRegex : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedJsonRequired() {
        return expectedJsonRequired;
    }

    public EvalTurn setExpectedJsonRequired(List<String> expectedJsonRequired) {
        this.expectedJsonRequired = expectedJsonRequired != null ? expectedJsonRequired : new ArrayList<>();
        return this;
    }

    public List<String> getExpectedJsonAbsent() {
        return expectedJsonAbsent;
    }

    public EvalTurn setExpectedJsonAbsent(List<String> expectedJsonAbsent) {
        this.expectedJsonAbsent = expectedJsonAbsent != null ? expectedJsonAbsent : new ArrayList<>();
        return this;
    }

    public Map<String, Object> getExpectedJsonValues() {
        return expectedJsonValues;
    }

    public EvalTurn setExpectedJsonValues(Map<String, Object> expectedJsonValues) {
        this.expectedJsonValues = expectedJsonValues;
        return this;
    }

    public List<String> getExpectedTools() {
        return expectedTools;
    }

    public EvalTurn setExpectedTools(List<String> expectedTools) {
        this.expectedTools = expectedTools != null ? expectedTools : new ArrayList<>();
        return this;
    }

    public List<String> getForbiddenTools() {
        return forbiddenTools;
    }

    public EvalTurn setForbiddenTools(List<String> forbiddenTools) {
        this.forbiddenTools = forbiddenTools != null ? forbiddenTools : new ArrayList<>();
        return this;
    }

    public String getExpectedStopReason() {
        return expectedStopReason;
    }

    public EvalTurn setExpectedStopReason(String expectedStopReason) {
        this.expectedStopReason = expectedStopReason;
        return this;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public EvalTurn setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }
}
