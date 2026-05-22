package ricbot.domain.config;

import ricbot.infra.config.Config;

import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderCapability(String providerName, ModelCapability modelCapability, String source) {
    public static final String SOURCE_STATIC = "STATIC";
    public static final String SOURCE_HEURISTIC = "HEURISTIC";
    public static final String SOURCE_USER_OVERRIDE = "USER_OVERRIDE";
    public static final String SOURCE_MIXED = "MIXED";

    public ProviderCapability(String providerName, ModelCapability modelCapability) {
        this(providerName, modelCapability, SOURCE_HEURISTIC);
    }

    public static ProviderCapability infer(Config config, String providerName, String model) {
        return new ProviderCapabilityResolver().resolve(config, providerName, model);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("providerName", providerName);
        map.put("source", source);
        map.putAll(modelCapability.toMap());
        return map;
    }
}
