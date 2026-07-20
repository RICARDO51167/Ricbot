package ricbot.domain.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigDoctorReport {
    private String configPath;
    private String workspace;
    private String model;
    private String inferredProvider;
    private String apiBase;
    private boolean apiKeyPresent;
    private Map<String, Object> effectivePorts = new LinkedHashMap<>();
    private Map<String, Object> enabledTools = new LinkedHashMap<>();
    private List<Map<String, Object>> mcpServers = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> ignoredFields = new ArrayList<>();
    private final List<String> suggestedFixes = new ArrayList<>();
    private ProviderCapability providerCapability;

    public String status() {
        if (!errors.isEmpty()) {
            return "ERROR";
        }
        if (!warnings.isEmpty() || !ignoredFields.isEmpty()) {
            return "WARNING";
        }
        return "OK";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status());
        map.put("configPath", configPath);
        map.put("workspace", workspace);
        map.put("model", model);
        map.put("inferredProvider", inferredProvider);
        map.put("apiBase", apiBase);
        map.put("apiKeyPresent", apiKeyPresent);
        map.put("effectivePorts", effectivePorts);
        map.put("enabledTools", enabledTools);
        map.put("mcpServers", mcpServers);
        map.put("errors", errors);
        map.put("warnings", warnings);
        map.put("ignoredFields", ignoredFields);
        map.put("suggestedFixes", suggestedFixes);
        map.put("providerCapability", providerCapability != null ? providerCapability.toMap() : null);
        return map;
    }

    public void addError(String error) {
        addUnique(errors, error);
    }

    public void addWarning(String warning) {
        addUnique(warnings, warning);
    }

    public void addIgnoredField(String field) {
        addUnique(ignoredFields, field);
    }

    public void addSuggestedFix(String fix) {
        addUnique(suggestedFixes, fix);
    }

    private static void addUnique(List<String> target, String value) {
        if (value != null && !value.isBlank() && !target.contains(value)) {
            target.add(value);
        }
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getInferredProvider() {
        return inferredProvider;
    }

    public void setInferredProvider(String inferredProvider) {
        this.inferredProvider = inferredProvider;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public boolean isApiKeyPresent() {
        return apiKeyPresent;
    }

    public void setApiKeyPresent(boolean apiKeyPresent) {
        this.apiKeyPresent = apiKeyPresent;
    }

    public Map<String, Object> getEffectivePorts() {
        return effectivePorts;
    }

    public void setEffectivePorts(Map<String, Object> effectivePorts) {
        this.effectivePorts = effectivePorts != null ? effectivePorts : new LinkedHashMap<>();
    }

    public Map<String, Object> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(Map<String, Object> enabledTools) {
        this.enabledTools = enabledTools != null ? enabledTools : new LinkedHashMap<>();
    }

    public List<Map<String, Object>> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(List<Map<String, Object>> mcpServers) {
        this.mcpServers = mcpServers != null ? mcpServers : new ArrayList<>();
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getIgnoredFields() {
        return ignoredFields;
    }

    public List<String> getSuggestedFixes() {
        return suggestedFixes;
    }

    public ProviderCapability getProviderCapability() {
        return providerCapability;
    }

    public void setProviderCapability(ProviderCapability providerCapability) {
        this.providerCapability = providerCapability;
    }
}
