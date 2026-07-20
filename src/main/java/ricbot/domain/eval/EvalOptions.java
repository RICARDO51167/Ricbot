package ricbot.domain.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EvalOptions {
    private Path scenariosPath;
    private Path outputDir;
    private String sessionPrefix;
    private int limit;
    private boolean failFast;
    private boolean allowUnsafeWorkspaceClean;
    private boolean restoreWorkspace = true;
    private boolean restoreSession = true;
    private List<String> includeTags = new ArrayList<>();
    private List<String> excludeTags = new ArrayList<>();

    public Path getScenariosPath() {
        return scenariosPath;
    }

    public EvalOptions setScenariosPath(Path scenariosPath) {
        this.scenariosPath = scenariosPath;
        return this;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public EvalOptions setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
        return this;
    }

    public String getSessionPrefix() {
        return sessionPrefix;
    }

    public EvalOptions setSessionPrefix(String sessionPrefix) {
        this.sessionPrefix = sessionPrefix;
        return this;
    }

    public int getLimit() {
        return limit;
    }

    public EvalOptions setLimit(int limit) {
        this.limit = limit;
        return this;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public EvalOptions setFailFast(boolean failFast) {
        this.failFast = failFast;
        return this;
    }

    public boolean isAllowUnsafeWorkspaceClean() {
        return allowUnsafeWorkspaceClean;
    }

    public EvalOptions setAllowUnsafeWorkspaceClean(boolean allowUnsafeWorkspaceClean) {
        this.allowUnsafeWorkspaceClean = allowUnsafeWorkspaceClean;
        return this;
    }

    public boolean isRestoreWorkspace() {
        return restoreWorkspace;
    }

    public EvalOptions setRestoreWorkspace(boolean restoreWorkspace) {
        this.restoreWorkspace = restoreWorkspace;
        return this;
    }

    public boolean isRestoreSession() {
        return restoreSession;
    }

    public EvalOptions setRestoreSession(boolean restoreSession) {
        this.restoreSession = restoreSession;
        return this;
    }

    public List<String> getIncludeTags() {
        return includeTags;
    }

    public EvalOptions setIncludeTags(List<String> includeTags) {
        this.includeTags = includeTags != null ? includeTags : new ArrayList<>();
        return this;
    }

    public List<String> getExcludeTags() {
        return excludeTags;
    }

    public EvalOptions setExcludeTags(List<String> excludeTags) {
        this.excludeTags = excludeTags != null ? excludeTags : new ArrayList<>();
        return this;
    }
}
