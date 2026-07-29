package ricbot.infra.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载器类，负责加载、保存和处理 Ricbot 的配置信息。
 * 对应 Python: loader.py
 */
@Slf4j
public final class ConfigLoader {

    // Jackson ObjectMapper 实例，用于 JSON 序列化和反序列化，并自动注册找到的模块
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };
    
    // 正则表达式模式，用于匹配配置文件中的环境变量占位符，格式为 ${VAR_NAME}
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    /**
     * 当前配置文件的路径。
     * 对应 Python: _current_config_path
     */
    private static Path currentConfigPath;

    // 私有构造函数，防止实例化，因为这是一个工具类
    private ConfigLoader() {
    }

    /**
     * 设置当前配置文件的路径。
     *
     * @param path 配置文件路径，如果为 null 则重置
     */
    public static void setConfigPath(Path path) {
        // 如果路径不为空，则转换为绝对路径并规范化；否则设为 null
        currentConfigPath = normalizePath(path);
    }

    /**
     * 获取当前有效的配置文件路径。
     * 优先级：手动设置的路径 > 环境变量 RICBOT_CONFIG > 系统属性 ricbot.config > 默认用户目录下的 config.json
     *
     * @return 配置文件的绝对规范路径
     */
    public static Path getConfigPath() {
        // 1. 检查是否手动设置了路径
        if (currentConfigPath != null) {
            return currentConfigPath;
        }

        // 2. 检查环境变量 RICBOT_CONFIG
        Path envPath = configuredPath(System.getenv("RICBOT_CONFIG"));
        if (envPath != null) {
            return envPath;
        }

        // 3. 检查系统属性 ricbot.config
        Path propertyPath = configuredPath(System.getProperty("ricbot.config"));
        if (propertyPath != null) {
            return propertyPath;
        }

        // 4. 返回默认路径：用户主目录/.ricbot/config.json
        return defaultConfigPath();
    }

    /**
     * 加载配置文件，如果加载失败则返回默认配置。
     *
     * @return 配置对象
     */
    public static Config loadOrDefault() {
        try {
            return loadConfig();
        } catch (Exception e) {
            Path path = getConfigPath();
            log.warn("加载配置失败，改用默认配置: {}", path, e);
            return defaultConfig();
        }
    }

    /**
     * 加载配置文件，使用默认路径。
     *
     * @return 配置对象
     */
    public static Config loadConfig() {
        return loadConfig(null);
    }

    /**
     * 从指定路径或默认路径加载配置文件。
     * 对应 Python: load_config(config_path=None)
     *
     * @param configPath 配置文件路径，如果为 null 则使用默认路径
     * @return 配置对象，如果加载失败则返回默认配置
     */
    public static Config loadConfig(Path configPath) {
        // 确定最终使用的配置路径
        Path path = resolveConfigPath(configPath);
        // 创建一个新的配置对象作为基础
        Config config = defaultConfig();

        // 如果配置文件存在，则尝试读取和解析
        if (Files.exists(path)) {
            try {
                // 将 JSON 文件读取为 Map 结构
                Map<String, Object> raw = MAPPER.readValue(path.toFile(), JSON_OBJECT_TYPE);
                // 将 Map 转换为 Config 对象
                config = mapToConfig(raw);
            } catch (Exception e) {
                log.warn("从 {} 加载配置失败，将使用默认配置。", path, e);
            }
        }

        return config;
    }

    /**
     * 保存配置到默认路径。
     * 对应 Python: save_config(...)
     *
     * @param config 要保存的配置对象
     */
    public static void saveConfig(Config config) {
        saveConfig(config, null);
    }

    /**
     * 保存配置到指定路径或默认路径。
     *
     * @param config     要保存的配置对象
     * @param configPath 目标路径，如果为 null 则使用默认路径
     */
    public static void saveConfig(Config config, Path configPath) {
        // 确定最终保存的路径
        Path path = resolveConfigPath(configPath);
        Config actualConfig = safeConfig(config);

        try {
            // 确保父目录存在，如果不存在则创建
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 将 Config 对象转换为 Map，并以美观的格式写入 JSON 文件
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), configToMap(actualConfig));
            hardenConfigFilePermissions(path);
        } catch (IOException e) {
            // 如果发生 IO 异常，抛出运行时异常
            throw new RuntimeException("保存配置失败：" + path, e);
        }
    }

    private static void hardenConfigFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(
                    path,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file systems such as Windows do not support chmod-style permissions.
        } catch (IOException e) {
            log.warn("无法收紧配置文件权限: {}", path, e);
        }
    }

    /**
     * 解析配置中的环境变量占位符。
     * 对应 Python: resolve_config_env_vars(config)
     *
     * @param config 原始配置对象
     * @return 解析后的配置对象，其中 ${VAR} 已被替换为实际的环境变量值
     */
    public static Config resolveConfigEnvVars(Config config) {
        // 将 Config 对象转换为 Map
        Map<String, Object> raw = configToMap(safeConfig(config));
        // 递归解析 Map 中的环境变量
        Object resolved = resolveEnvVars(raw);
        Map<String, Object> map = asMap(resolved);
        // 将解析后的 Map 转换回 Config 对象
        return mapToConfig(map);
    }

    private static Path resolveConfigPath(Path configPath) {
        return configPath != null ? normalizePath(configPath) : getConfigPath();
    }

    private static Path configuredPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizePath(Path.of(value));
    }

    private static Path normalizePath(Path path) {
        return path != null ? path.toAbsolutePath().normalize() : null;
    }

    private static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), ".ricbot", "config.json")
                .toAbsolutePath()
                .normalize();
    }

    private static Config defaultConfig() {
        return new Config();
    }

    private static Config safeConfig(Config config) {
        return config != null ? config : defaultConfig();
    }

    /**
     * 递归解析对象中的环境变量占位符。
     * 支持 String、Map 和 List 类型。
     *
     * @param obj 待解析的对象
     * @return 解析后的对象
     */
    private static Object resolveEnvVars(Object obj) {
        // 如果是字符串，检查并替换环境变量
        if (obj instanceof String s) {
            Matcher m = ENV_PATTERN.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                // 获取变量名
                String name = m.group(1);
                // 获取环境变量值
                String value = System.getenv(name);
                if (value == null) {
                    value = m.group(0);
                }
                // 替换占位符，使用 quoteReplacement 防止特殊字符干扰
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
            // 添加尾部剩余字符串
            m.appendTail(sb);
            return sb.toString();
        }

        // 如果是 Map，递归处理每个值
        if (obj instanceof Map<?, ?> rawMap) {
            Map<String, Object> out = ricbot.infra.common.JsonMapUtils.copyObjectMap(rawMap);
            out.replaceAll((key, value) -> resolveEnvVars(value));
            return out;
        }

        // 如果是 List，递归处理每个元素
        if (obj instanceof List<?> rawList) {
            List<Object> out = new ArrayList<>();
            for (Object item : rawList) {
                out.add(resolveEnvVars(item));
            }
            return out;
        }

        // 其他类型直接返回
        return obj;
    }

    // =========================================================
    // Map <-> Config conversion
    // Map 与 Config 对象之间的转换方法
    // =========================================================

    /**
     * 将 Map 数据转换为 Config 对象。
     *
     * @param data 包含配置数据的 Map
     * @return Config 对象
     */
    private static Config mapToConfig(Map<String, Object> data) {
        // 创建新的 Config 实例
        Config config = new Config();
        // 如果数据为空，返回空配置
        if (data == null) {
            return config;
        }

        // --- 处理 agents.defaults 部分 ---
        // 获取 agents 节点，如果不存在则返回空 Map
        Map<String, Object> agents = asMap(data.get("agents"));
        // 获取 defaults 节点
        Map<String, Object> defaults = asMap(agents.get("defaults"));
        // 获取 Config 中的 AgentDefaults 对象
        Config.AgentDefaults ad = config.getAgents().getDefaults();
        
        // 设置各项默认属性，使用辅助方法处理类型转换和默认值
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
        ad.setSessionTtlMinutes(intValue(defaults.get("session_ttl_minutes"), ad.getSessionTtlMinutes()));
        Map<String, Object> budget = asMap(defaults.get("budget"));
        Config.BudgetConfig bc = ad.getBudget();
        bc.setMaxTotalTokens(longValue(budget.get("max_total_tokens"), bc.getMaxTotalTokens()));
        bc.setMaxCostMicrousd(longValue(budget.get("max_cost_microusd"), bc.getMaxCostMicrousd()));
        bc.setMaxActiveSeconds(longValue(budget.get("max_active_seconds"), bc.getMaxActiveSeconds()));
        bc.setMaxToolCalls(longValue(budget.get("max_tool_calls"), bc.getMaxToolCalls()));
        bc.setFinalizationTokens(longValue(budget.get("finalization_tokens"), bc.getFinalizationTokens()));
        Map<String, Object> worker = asMap(budget.get("worker"));
        Config.WorkerBudgetConfig wc = bc.getWorker();
        wc.setAllocation(string(worker.get("allocation"), wc.getAllocation()));
        wc.setMaxTotalTokens(longValue(worker.get("max_total_tokens"), wc.getMaxTotalTokens()));
        wc.setMaxCostMicrousd(longValue(worker.get("max_cost_microusd"), wc.getMaxCostMicrousd()));
        wc.setMaxActiveSeconds(longValue(worker.get("max_active_seconds"), wc.getMaxActiveSeconds()));
        wc.setMaxToolCalls(longValue(worker.get("max_tool_calls"), wc.getMaxToolCalls()));
        Map<String, Object> offload = asMap(defaults.get("context_offload"));
        Config.ContextOffloadConfig oc = ad.getContextOffload();
        oc.setEnabled(booleanValue(offload.get("enabled"), oc.isEnabled()));
        oc.setPreviewChars(intValue(offload.get("preview_chars"), oc.getPreviewChars()));
        oc.setReadChunkChars(intValue(offload.get("read_chunk_chars"), oc.getReadChunkChars()));

        // --- 处理 providers 部分 ---
        Map<String, Object> providers = asMap(data.get("providers"));
        // 遍历所有 provider
        for (String name : providers.keySet()) {
            // 获取对应的 ProviderConfig 对象，如果不存在则跳过
            Config.ProviderConfig pc = config.getProviders().getOrCreate(name);
            if (pc == null) {
                continue;
            }
            // 获取 provider 的具体配置 Map
            Map<String, Object> p = asMap(providers.get(name));
            pc.setApiKey(string(p.get("api_key"), pc.getApiKey()));
            pc.setApiBase(string(p.get("api_base"), pc.getApiBase()));
            pc.setExtraHeaders(stringMap(p.get("extra_headers")));
        }

        // --- 处理 model_capabilities 部分 ---
        config.setModelCapabilities(parseModelCapabilities(
                data.get("model_capabilities")
        ));
        Config.ModelCardsConfig modelCards = new Config.ModelCardsConfig();
        modelCards.setPaths(stringList(asMap(data.get("model_cards")).get("paths")));
        config.setModelCards(modelCards);

        // --- 处理 tools 部分 ---
        Map<String, Object> tools = asMap(data.get("tools"));
        // 设置 restrictToWorkspace
        config.getTools().setRestrictToWorkspace(booleanValue(
                tools.get("restrictToWorkspace"),
                config.getTools().isRestrictToWorkspace()
        ));

        // 处理 exec 工具配置
        Map<String, Object> exec = asMap(tools.get("exec"));
        Config.ExecToolConfig ec = config.getTools().getExec();
        ec.setEnable(booleanValue(exec.get("enable"), ec.isEnable()));
        ec.setTimeout(intValue(exec.get("timeout"), ec.getTimeout()));
        ec.setSandbox(booleanValue(exec.get("sandbox"), ec.isSandbox()));
        ec.setApprovalEnabled(booleanValue(exec.get("approval_enabled"), ec.isApprovalEnabled()));
        ec.setBackend(string(exec.get("backend"), ec.getBackend()));
        ec.setFallbackBackend(string(exec.get("fallback_backend"), ec.getFallbackBackend()));
        ec.setAllowBackendFallback(booleanValue(exec.get("allow_backend_fallback"), ec.isAllowBackendFallback()));
        ec.setDockerImage(string(exec.get("docker_image"), ec.getDockerImage()));
        ec.setDockerNetworkEnabled(booleanValue(exec.get("docker_network_enabled"), ec.isDockerNetworkEnabled()));
        ec.setPathAppend(string(exec.get("path_append"), ec.getPathAppend()));
        ec.setAllowedEnvKeys(stringList(exec.get("allowed_env_keys")));

        return config;
    }

    /**
     * 将 Config 对象转换为 Map 结构，用于序列化。
     *
     * @param config 配置对象
     * @return 包含配置数据的 Map
     */
    private static Map<String, Object> configToMap(Config config) {
        // 创建根 Map
        Map<String, Object> root = new LinkedHashMap<>();

        // --- 构建 agents 部分 ---
        Map<String, Object> agents = new LinkedHashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        Config.AgentDefaults ad = config.getAgents().getDefaults();
        
        // 填充 defaults 各项属性
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
        defaults.put("session_ttl_minutes", ad.getSessionTtlMinutes());
        Config.BudgetConfig bc = ad.getBudget();
        Map<String, Object> workerBudget = new LinkedHashMap<>();
        workerBudget.put("allocation", bc.getWorker().getAllocation());
        workerBudget.put("max_total_tokens", bc.getWorker().getMaxTotalTokens());
        workerBudget.put("max_cost_microusd", bc.getWorker().getMaxCostMicrousd());
        workerBudget.put("max_active_seconds", bc.getWorker().getMaxActiveSeconds());
        workerBudget.put("max_tool_calls", bc.getWorker().getMaxToolCalls());
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("max_total_tokens", bc.getMaxTotalTokens());
        budget.put("max_cost_microusd", bc.getMaxCostMicrousd());
        budget.put("max_active_seconds", bc.getMaxActiveSeconds());
        budget.put("max_tool_calls", bc.getMaxToolCalls());
        budget.put("finalization_tokens", bc.getFinalizationTokens());
        budget.put("worker", workerBudget);
        defaults.put("budget", budget);
        Config.ContextOffloadConfig oc = ad.getContextOffload();
        defaults.put("context_offload", Map.of("enabled", oc.isEnabled(), "preview_chars", oc.getPreviewChars(),
                "read_chunk_chars", oc.getReadChunkChars()));

        agents.put("defaults", defaults);
        root.put("agents", agents);

        // --- 构建 providers 部分 ---
        Map<String, Object> providers = new LinkedHashMap<>();
        // 遍历所有 provider 并转换为 Map
        for (Map.Entry<String, Config.ProviderConfig> entry : config.getProviders().asMap().entrySet()) {
            Config.ProviderConfig pc = entry.getValue();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("api_key", pc.getApiKey());
            p.put("api_base", pc.getApiBase());
            p.put("extra_headers", pc.getExtraHeaders());
            providers.put(entry.getKey(), p);
        }
        root.put("providers", providers);

        Map<String, Object> modelCapabilities = new LinkedHashMap<>();
        for (Map.Entry<String, Config.ModelCapabilityOverride> entry : config.getModelCapabilities().entrySet()) {
            Config.ModelCapabilityOverride override = entry.getValue();
            if (override == null || !override.hasAnyField()) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            putIfNotNull(value, "supportsToolCalling", override.getSupportsToolCalling());
            putIfNotNull(value, "supportsStreaming", override.getSupportsStreaming());
            putIfNotNull(value, "supportsVision", override.getSupportsVision());
            putIfNotNull(value, "supportsJsonMode", override.getSupportsJsonMode());
            putIfNotNull(value, "supportsReasoningEffort", override.getSupportsReasoningEffort());
            putIfNotNull(value, "contextWindowTokens", override.getContextWindowTokens());
            putIfNotNull(value, "maxOutputTokens", override.getMaxOutputTokens());
            putIfNotNull(value, "apiMode", override.getApiMode());
            modelCapabilities.put(entry.getKey(), value);
        }
        root.put("model_capabilities", modelCapabilities);
        root.put("model_cards", Map.of("paths", config.getModelCards().getPaths()));

        // --- 构建 tools 部分 ---
        Map<String, Object> tools = new LinkedHashMap<>();
        
        // 构建 exec 配置
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("enable", config.getTools().getExec().isEnable());
        exec.put("timeout", config.getTools().getExec().getTimeout());
        exec.put("sandbox", config.getTools().getExec().isSandbox());
        exec.put("approval_enabled", config.getTools().getExec().isApprovalEnabled());
        exec.put("backend", config.getTools().getExec().getBackend());
        exec.put("fallback_backend", config.getTools().getExec().getFallbackBackend());
        exec.put("allow_backend_fallback", config.getTools().getExec().isAllowBackendFallback());
        exec.put("docker_image", config.getTools().getExec().getDockerImage());
        exec.put("docker_network_enabled", config.getTools().getExec().isDockerNetworkEnabled());
        exec.put("path_append", config.getTools().getExec().getPathAppend());
        exec.put("allowed_env_keys", config.getTools().getExec().getAllowedEnvKeys());
        tools.put("exec", exec);

        // 设置 tools 的其他属性
        tools.put("restrictToWorkspace", config.getTools().isRestrictToWorkspace());
        root.put("tools", tools);

        return root;
    }

    // helpers
    // 辅助方法区域

    private static Map<String, Config.ModelCapabilityOverride> parseModelCapabilities(Object value) {
        Map<String, Object> raw = asMap(value);
        Map<String, Config.ModelCapabilityOverride> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String model = entry.getKey() != null ? entry.getKey().trim() : "";
            if (model.isBlank()) {
                continue;
            }
            Map<String, Object> map = asMap(entry.getValue());
            Config.ModelCapabilityOverride override = new Config.ModelCapabilityOverride();
            override.setSupportsToolCalling(capabilityFlag(map.get("supportsToolCalling")));
            override.setSupportsStreaming(capabilityFlag(map.get("supportsStreaming")));
            override.setSupportsVision(capabilityFlag(map.get("supportsVision")));
            override.setSupportsJsonMode(capabilityFlag(map.get("supportsJsonMode")));
            override.setSupportsReasoningEffort(capabilityFlag(map.get("supportsReasoningEffort")));
            override.setContextWindowTokens(positiveInteger(map.get("contextWindowTokens")));
            override.setMaxOutputTokens(positiveInteger(map.get("maxOutputTokens")));
            override.setApiMode(string(map.get("apiMode"), null));
            if (override.hasAnyField()) {
                out.put(model, override);
            }
        }
        return out;
    }

    private static String capabilityFlag(Object value) {
        if (value instanceof Boolean b) {
            return Boolean.toString(b);
        }
        String text = string(value, null);
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return "true";
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return "false";
        }
        if ("unknown".equalsIgnoreCase(normalized)) {
            return "UNKNOWN";
        }
        return null;
    }

    private static Integer positiveInteger(Object value) {
        Integer parsed = integerValue(value, null);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 安全地将对象转换为 String-Object Map。
     * 如果对象不是 Map，则返回空的 LinkedHashMap。
     *
     * @param o 待转换的对象
     * @return Map<String, Object>
     */
    private static Map<String, Object> asMap(Object o) {
        return ricbot.infra.common.JsonMapUtils.asObjectMap(o);
    }

    /**
     * 安全地将对象转换为 String-String Map。
     *
     * @param o 待转换的对象
     * @return Map<String, String>
     */
    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                // 将 key 和 value 都转换为 String，value 为 null 时保留 null
                out.put(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : null);
            }
        }
        return out;
    }

    /**
     * 安全地将对象转换为 String List。
     *
     * @param o 待转换的对象
     * @return List<String>
     */
    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                // 忽略 null 元素
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    /**
     * 获取字符串值，如果对象为 null 则返回默认值。
     *
     * @param o   对象
     * @param def 默认值
     * @return 字符串
     */
    private static String string(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        s = normalizeQuoted(s);
        return s.isBlank() ? def : s;
    }

    private static String normalizeQuoted(String raw) {
        if (raw == null) {
            return "";
        }
        String s = normalizeWhitespace(raw);
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        if (s.length() >= 2 && s.charAt(0) == '`' && s.charAt(s.length() - 1) == '`') {
            s = s.substring(1, s.length() - 1).trim();
        }
        return normalizeWhitespace(s);
    }

    private static String normalizeWhitespace(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw
                .replace('\u00A0', ' ')
                .replace("\u200B", "")
                .replace("\uFEFF", "");
        return s.trim();
    }

    /**
     * 获取 int 值，如果对象不是数字或解析失败则返回默认值。
     *
     * @param o   对象
     * @param def 默认值
     * @return int
     */
    private static int intValue(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o != null ? Integer.parseInt(String.valueOf(o)) : def; } catch (Exception e) { return def; }
    }

    /**
     * 获取 Integer 值，如果对象不是数字或解析失败则返回默认值。
     *
     * @param o   对象
     * @param def 默认值
     * @return Integer
     */
    private static Integer integerValue(Object o, Integer def) {
        if (o instanceof Number n) return n.intValue();
        try { return o != null ? Integer.valueOf(String.valueOf(o)) : def; } catch (Exception e) { return def; }
    }

    private static Long longValue(Object o, Long def) {
        if (o instanceof Number n) return n.longValue();
        try { return o != null ? Long.valueOf(String.valueOf(o)) : def; } catch (Exception e) { return def; }
    }

    /**
     * 获取 double 值，如果对象不是数字或解析失败则返回默认值。
     *
     * @param o   对象
     * @param def 默认值
     * @return double
     */
    private static double doubleValue(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o != null ? Double.parseDouble(String.valueOf(o)) : def; } catch (Exception e) { return def; }
    }

    /**
     * 获取 boolean 值，如果对象不是 Boolean 类型则尝试解析字符串，失败则返回默认值。
     *
     * @param o   对象
     * @param def 默认值
     * @return boolean
     */
    private static boolean booleanValue(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(String.valueOf(o));
        return def;
    }

}
