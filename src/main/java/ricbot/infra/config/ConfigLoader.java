package ricbot.infra.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.security.NetworkSecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对应 Python: loader.py
 */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    /**
     * 对应 Python: _current_config_path
     */
    private static Path currentConfigPath;

    private ConfigLoader() {
    }

    public static void setConfigPath(Path path) {
        currentConfigPath = path != null ? path.toAbsolutePath().normalize() : null;
    }

    public static Path getConfigPath() {
        if (currentConfigPath != null) {
            return currentConfigPath;
        }
        String env = System.getenv("RICBOT_CONFIG");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        String prop = System.getProperty("ricbot.config");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".ricbot", "config.json")
                .toAbsolutePath()
                .normalize();
    }

    public static Config loadConfig() {
        return loadConfig(null);
    }

    /**
     * 对应 Python: load_config(config_path=None)
     */
    public static Config loadConfig(Path configPath) {
        Path path = configPath != null ? configPath.toAbsolutePath().normalize() : getConfigPath();
        Config config = new Config();

        if (Files.exists(path)) {
            try {
                Map<String, Object> raw = MAPPER.readValue(path.toFile(), new TypeReference<>() {});
                raw = migrateConfig(raw);
                config = mapToConfig(raw);
            } catch (Exception e) {
                System.err.println("Failed to load config from " + path + ": " + e.getMessage());
                System.err.println("Using default configuration.");
            }
        }

        applySsrfWhitelist(config);
        return config;
    }

    /**
     * 对应 Python: save_config(...)
     */
    public static void saveConfig(Config config) {
        saveConfig(config, null);
    }

    public static void saveConfig(Config config, Path configPath) {
        Path path = configPath != null ? configPath.toAbsolutePath().normalize() : getConfigPath();

        try {
            Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), configToMap(config));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config to " + path, e);
        }
    }

    /**
     * 对应 Python: resolve_config_env_vars(config)
     */
    public static Config resolveConfigEnvVars(Config config) {
        Map<String, Object> raw = configToMap(config);
        Object resolved = resolveEnvVars(raw);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) resolved;
        return mapToConfig(map);
    }

    private static Object resolveEnvVars(Object obj) {
        if (obj instanceof String s) {
            Matcher m = ENV_PATTERN.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String name = m.group(1);
                String value = System.getenv(name);
                if (value == null) {
                    throw new IllegalArgumentException(
                            "Environment variable '" + name + "' referenced in config is not set"
                    );
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        if (obj instanceof Map<?, ?> rawMap) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                out.put(String.valueOf(entry.getKey()), resolveEnvVars(entry.getValue()));
            }
            return out;
        }

        if (obj instanceof List<?> rawList) {
            List<Object> out = new ArrayList<>();
            for (Object item : rawList) {
                out.add(resolveEnvVars(item));
            }
            return out;
        }

        return obj;
    }

    /**
     * 对应 Python: _migrate_config(data)
     *
     * 目前只迁移：
     * tools.exec.restrictToWorkspace -> tools.restrictToWorkspace
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> migrateConfig(Map<String, Object> data) {
        if (data == null) {
            return new LinkedHashMap<>();
        }

        Object toolsObj = data.get("tools");
        if (!(toolsObj instanceof Map<?, ?> rawTools)) {
            return data;
        }

        Map<String, Object> tools = (Map<String, Object>) rawTools;
        Object execObj = tools.get("exec");
        if (execObj instanceof Map<?, ?> rawExec) {
            Map<String, Object> exec = (Map<String, Object>) rawExec;
            if (exec.containsKey("restrictToWorkspace") && !tools.containsKey("restrictToWorkspace")) {
                tools.put("restrictToWorkspace", exec.remove("restrictToWorkspace"));
            }
        }
        return data;
    }

    private static void applySsrfWhitelist(Config config) {
        if (config == null || config.getTools() == null) {
            return;
        }
        NetworkSecurity.configureSsrfWhitelist(config.getTools().getSsrfWhitelist());
    }

    // =========================================================
    // Map <-> Config conversion
    // =========================================================

    @SuppressWarnings("unchecked")
    private static Config mapToConfig(Map<String, Object> data) {
        Config config = new Config();
        if (data == null) {
            return config;
        }

        // agents.defaults
        Map<String, Object> agents = asMap(data.get("agents"));
        Map<String, Object> defaults = asMap(agents.get("defaults"));
        Config.AgentDefaults ad = config.getAgents().getDefaults();
        ad.setModel(string(defaults.get("model"), ad.getModel()));
        ad.setWorkspace(string(defaults.get("workspace"), ad.getWorkspace()));
        ad.setTemperature(doubleValue(defaults.get("temperature"), ad.getTemperature()));
        ad.setMaxTokens(intValue(defaults.get("max_tokens"), ad.getMaxTokens()));
        ad.setReasoningEffort(string(defaults.get("reasoning_effort"), ad.getReasoningEffort()));
        ad.setMaxToolIterations(intValue(defaults.get("max_tool_iterations"), ad.getMaxToolIterations()));
        ad.setContextWindowTokens(integerValue(defaults.get("context_window_tokens"), ad.getContextWindowTokens()));
        ad.setContextBlockLimit(integerValue(defaults.get("context_block_limit"), ad.getContextBlockLimit()));
        ad.setMaxToolResultChars(intValue(defaults.get("max_tool_result_chars"), ad.getMaxToolResultChars()));
        ad.setProviderRetryMode(string(defaults.get("provider_retry_mode"), ad.getProviderRetryMode()));
        ad.setTimezone(string(defaults.get("timezone"), ad.getTimezone()));
        ad.setUnifiedSession(booleanValue(defaults.get("unified_session"), ad.isUnifiedSession()));
        ad.setDisabledSkills(stringList(defaults.get("disabled_skills")));
        ad.setSessionTtlMinutes(intValue(defaults.get("session_ttl_minutes"), ad.getSessionTtlMinutes()));

        Map<String, Object> dream = asMap(defaults.get("dream"));
        Config.DreamConfig dc = ad.getDream();
        dc.setEnabled(booleanValue(dream.get("enabled"), dc.isEnabled()));
        dc.setModelOverride(string(dream.get("model_override"), dc.getModelOverride()));
        dc.setMaxBatchSize(intValue(dream.get("max_batch_size"), dc.getMaxBatchSize()));
        dc.setMaxIterations(intValue(dream.get("max_iterations"), dc.getMaxIterations()));
        dc.setCron(string(dream.get("cron"), dc.getCron()));

        // providers
        Map<String, Object> providers = asMap(data.get("providers"));
        for (String name : providers.keySet()) {
            Config.ProviderConfig pc = config.getProviders().get(name);
            if (pc == null) {
                continue;
            }
            Map<String, Object> p = asMap(providers.get(name));
            pc.setApiKey(string(p.get("api_key"), string(p.get("apiKey"), pc.getApiKey())));
            pc.setApiBase(string(p.get("api_base"), string(p.get("apiBase"), pc.getApiBase())));
            pc.setExtraHeaders(stringMap(p.containsKey("extra_headers") ? p.get("extra_headers") : p.get("extraHeaders")));
        }

        // tools
        Map<String, Object> tools = asMap(data.get("tools"));
        config.getTools().setRestrictToWorkspace(booleanValue(
                tools.get("restrictToWorkspace"),
                config.getTools().isRestrictToWorkspace()
        ));
        config.getTools().setSsrfWhitelist(stringList(tools.get("ssrf_whitelist")));
        config.getTools().setMcpServers(asMap(tools.get("mcp_servers")));

        Map<String, Object> web = asMap(tools.get("web"));
        Config.WebToolsConfig wc = config.getTools().getWeb();
        wc.setEnable(booleanValue(web.get("enable"), wc.isEnable()));
        wc.setProxy(string(web.get("proxy"), wc.getProxy()));

        Map<String, Object> webSearch = asMap(web.get("search"));
        Config.WebSearchConfig wsc = wc.getSearch();
        wsc.setProvider(string(webSearch.get("provider"), wsc.getProvider()));
        wsc.setApiKey(string(webSearch.get("api_key"), wsc.getApiKey()));
        wsc.setBaseUrl(string(webSearch.get("base_url"), wsc.getBaseUrl()));
        wsc.setMaxResults(intValue(webSearch.get("max_results"), wsc.getMaxResults()));
        wsc.setTimeout(intValue(webSearch.get("timeout"), wsc.getTimeout()));

        Map<String, Object> exec = asMap(tools.get("exec"));
        Config.ExecToolConfig ec = config.getTools().getExec();
        ec.setEnable(booleanValue(exec.get("enable"), ec.isEnable()));
        ec.setTimeout(intValue(exec.get("timeout"), ec.getTimeout()));
        ec.setSandbox(booleanValue(exec.get("sandbox"), ec.isSandbox()));
        ec.setPathAppend(string(exec.get("path_append"), ec.getPathAppend()));
        ec.setAllowedEnvKeys(stringList(exec.get("allowed_env_keys")));

        // channels
        Map<String, Object> channels = asMap(data.get("channels"));
        config.getChannels().setSendProgress(booleanValue(
                channels.get("send_progress"),
                config.getChannels().isSendProgress()
        ));
        config.getChannels().setSendToolHints(booleanValue(
                channels.get("send_tool_hints"),
                config.getChannels().isSendToolHints()
        ));
        config.getChannels().setTranscriptionProvider(string(
                channels.get("transcription_provider"),
                config.getChannels().getTranscriptionProvider()
        ));

        // gateway
        Map<String, Object> gateway = asMap(data.get("gateway"));
        config.getGateway().setPort(intValue(gateway.get("port"), config.getGateway().getPort()));

        Map<String, Object> heartbeat = asMap(gateway.get("heartbeat"));
        Config.HeartbeatConfig hc = config.getGateway().getHeartbeat();
        hc.setEnabled(booleanValue(heartbeat.get("enabled"), hc.isEnabled()));
        hc.setIntervalS(intValue(heartbeat.get("interval_s"), hc.getIntervalS()));
        hc.setKeepRecentMessages(intValue(heartbeat.get("keep_recent_messages"), hc.getKeepRecentMessages()));

        // api
        Map<String, Object> api = asMap(data.get("api"));
        config.getApi().setHost(string(api.get("host"), config.getApi().getHost()));
        config.getApi().setPort(intValue(api.get("port"), config.getApi().getPort()));
        config.getApi().setTimeout(doubleValue(api.get("timeout"), config.getApi().getTimeout()));

        return config;
    }

    private static Map<String, Object> configToMap(Config config) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> agents = new LinkedHashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        Config.AgentDefaults ad = config.getAgents().getDefaults();
        defaults.put("model", ad.getModel());
        defaults.put("workspace", ad.getWorkspace());
        defaults.put("temperature", ad.getTemperature());
        defaults.put("max_tokens", ad.getMaxTokens());
        defaults.put("reasoning_effort", ad.getReasoningEffort());
        defaults.put("max_tool_iterations", ad.getMaxToolIterations());
        defaults.put("context_window_tokens", ad.getContextWindowTokens());
        defaults.put("context_block_limit", ad.getContextBlockLimit());
        defaults.put("max_tool_result_chars", ad.getMaxToolResultChars());
        defaults.put("provider_retry_mode", ad.getProviderRetryMode());
        defaults.put("timezone", ad.getTimezone());
        defaults.put("unified_session", ad.isUnifiedSession());
        defaults.put("disabled_skills", ad.getDisabledSkills());
        defaults.put("session_ttl_minutes", ad.getSessionTtlMinutes());

        Map<String, Object> dream = new LinkedHashMap<>();
        dream.put("enabled", ad.getDream().isEnabled());
        dream.put("model_override", ad.getDream().getModelOverride());
        dream.put("max_batch_size", ad.getDream().getMaxBatchSize());
        dream.put("max_iterations", ad.getDream().getMaxIterations());
        dream.put("cron", ad.getDream().getCron());
        defaults.put("dream", dream);

        agents.put("defaults", defaults);
        root.put("agents", agents);

        Map<String, Object> providers = new LinkedHashMap<>();
        for (Map.Entry<String, Config.ProviderConfig> entry : config.getProviders().asMap().entrySet()) {
            Config.ProviderConfig pc = entry.getValue();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("api_key", pc.getApiKey());
            p.put("api_base", pc.getApiBase());
            p.put("extra_headers", pc.getExtraHeaders());
            providers.put(entry.getKey(), p);
        }
        root.put("providers", providers);

        Map<String, Object> tools = new LinkedHashMap<>();
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("enable", config.getTools().getWeb().isEnable());
        web.put("proxy", config.getTools().getWeb().getProxy());
        Map<String, Object> webSearch = new LinkedHashMap<>();
        webSearch.put("provider", config.getTools().getWeb().getSearch().getProvider());
        webSearch.put("api_key", config.getTools().getWeb().getSearch().getApiKey());
        webSearch.put("base_url", config.getTools().getWeb().getSearch().getBaseUrl());
        webSearch.put("max_results", config.getTools().getWeb().getSearch().getMaxResults());
        webSearch.put("timeout", config.getTools().getWeb().getSearch().getTimeout());
        web.put("search", webSearch);
        tools.put("web", web);

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("enable", config.getTools().getExec().isEnable());
        exec.put("timeout", config.getTools().getExec().getTimeout());
        exec.put("sandbox", config.getTools().getExec().isSandbox());
        exec.put("path_append", config.getTools().getExec().getPathAppend());
        exec.put("allowed_env_keys", config.getTools().getExec().getAllowedEnvKeys());
        tools.put("exec", exec);

        tools.put("restrictToWorkspace", config.getTools().isRestrictToWorkspace());
        tools.put("ssrf_whitelist", config.getTools().getSsrfWhitelist());
        tools.put("mcp_servers", config.getTools().getMcpServers());
        root.put("tools", tools);

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("send_progress", config.getChannels().isSendProgress());
        channels.put("send_tool_hints", config.getChannels().isSendToolHints());
        channels.put("transcription_provider", config.getChannels().getTranscriptionProvider());
        root.put("channels", channels);

        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("port", config.getGateway().getPort());
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("enabled", config.getGateway().getHeartbeat().isEnabled());
        heartbeat.put("interval_s", config.getGateway().getHeartbeat().getIntervalS());
        heartbeat.put("keep_recent_messages", config.getGateway().getHeartbeat().getKeepRecentMessages());
        gateway.put("heartbeat", heartbeat);
        root.put("gateway", gateway);

        Map<String, Object> api = new LinkedHashMap<>();
        api.put("host", config.getApi().getHost());
        api.put("port", config.getApi().getPort());
        api.put("timeout", config.getApi().getTimeout());
        root.put("api", api);

        return root;
    }

    // helpers
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : null);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static String string(Object o, String def) {
        return o != null ? String.valueOf(o) : def;
    }

    private static int intValue(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o != null ? Integer.parseInt(String.valueOf(o)) : def; } catch (Exception e) { return def; }
    }

    private static Integer integerValue(Object o, Integer def) {
        if (o instanceof Number n) return n.intValue();
        try { return o != null ? Integer.valueOf(String.valueOf(o)) : def; } catch (Exception e) { return def; }
    }

    private static double doubleValue(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o != null ? Double.parseDouble(String.valueOf(o)) : def; } catch (Exception e) { return def; }
    }

    private static boolean booleanValue(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(String.valueOf(o));
        return def;
    }
}
