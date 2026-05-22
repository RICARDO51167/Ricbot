package ricbot.domain.config;

import ricbot.infra.config.Config;

import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderCapability(String providerName, ModelCapability modelCapability) {
    public static ProviderCapability infer(Config config, String providerName, String model) {
        return new ProviderCapabilityResolver().resolve(config, providerName, model);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("providerName", providerName);
        map.putAll(modelCapability.toMap());
        return map;
    }
}
