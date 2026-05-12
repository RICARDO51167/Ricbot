package ricbot.infra.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import ricbot.infra.security.NetworkSecurity;

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
                Map<String, Object> raw = MAPPER.readValue(path.toFile(), new TypeReference<>() {});
                // 执行配置迁移逻辑（处理旧版本配置结构）
                raw = migrateConfig(raw);
                // 将 Map 转换为 Config 对象
                config = mapToConfig(raw);
            } catch (Exception e) {
                log.warn("从 {} 加载配置失败，将使用默认配置。", path, e);
            }
        }

        // 应用 SSRF 白名单配置到网络安全模块
        applySsrfWhitelist(config);
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
        @SuppressWarnings("unchecked")
        // 强制转换回 Map 类型
        Map<String, Object> map = (Map<String, Object>) resolved;
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
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                out.put(String.valueOf(entry.getKey()), resolveEnvVars(entry.getValue()));
            }
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

    /**
     * 迁移旧版本的配置结构。
     * 对应 Python: _migrate_config(data)
     *
     * 目前只迁移：
     * tools.exec.restrictToWorkspace -> tools.restrictToWorkspace
     *
     * @param data 原始配置 Map
     * @return 迁移后的配置 Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> migrateConfig(Map<String, Object> data) {
        // 如果数据为空，返回空 Map
        if (data == null) {
            return new LinkedHashMap<>();
        }

        // 获取 tools 节点
        Object toolsObj = data.get("tools");
        // 如果 tools 不是 Map 类型，直接返回原数据
        if (!(toolsObj instanceof Map<?, ?> rawTools)) {
            return data;
        }

        Map<String, Object> tools = (Map<String, Object>) rawTools;
        // 获取 tools.exec 节点
        Object execObj = tools.get("exec");
        // 如果 exec 是 Map 类型
        if (execObj instanceof Map<?, ?> rawExec) {
            Map<String, Object> exec = (Map<String, Object>) rawExec;
            // 检查是否存在旧的 restrictToWorkspace 字段，且新位置不存在该字段
            if (exec.containsKey("restrictToWorkspace") && !tools.containsKey("restrictToWorkspace")) {
                // 将旧字段移动到新位置
                tools.put("restrictToWorkspace", exec.remove("restrictToWorkspace"));
            }
        }
        return data;
    }

    /**
     * 应用 SSRF 白名单配置到网络安全模块。
     *
     * @param config 配置对象
     */
    private static void applySsrfWhitelist(Config config) {
        // 如果配置或 tools 为空，则跳过
        if (config == null || config.getTools() == null) {
            return;
        }
        // 配置网络安全的 SSRF 白名单
        NetworkSecurity.configureSsrfWhitelist(config.getTools().getSsrfWhitelist());
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
    @SuppressWarnings("unchecked")
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
        ad.setDisabledSkills(stringList(defaults.get("disabled_skills")));
        ad.setSessionTtlMinutes(intValue(defaults.get("session_ttl_minutes"), ad.getSessionTtlMinutes()));

        // 处理 dream 配置
        Map<String, Object> dream = asMap(defaults.get("dream"));
        Config.DreamConfig dc = ad.getDream();
        dc.setEnabled(booleanValue(dream.get("enabled"), dc.isEnabled()));
        dc.setModelOverride(string(dream.get("model_override"), dc.getModelOverride()));
        dc.setMaxBatchSize(intValue(dream.get("max_batch_size"), dc.getMaxBatchSize()));
        dc.setMaxIterations(intValue(dream.get("max_iterations"), dc.getMaxIterations()));
        dc.setCron(string(dream.get("cron"), dc.getCron()));

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
            // 设置 apiKey，兼容 api_key 和 apiKey 两种键名
            pc.setApiKey(string(p.get("api_key"), string(p.get("apiKey"), pc.getApiKey())));
            // 设置 apiBase，兼容 api_base 和 apiBase 两种键名
            pc.setApiBase(string(p.get("api_base"), string(p.get("apiBase"), pc.getApiBase())));
            // 设置 extraHeaders，兼容 extra_headers 和 extraHeaders 两种键名
            pc.setExtraHeaders(stringMap(p.containsKey("extra_headers") ? p.get("extra_headers") : p.get("extraHeaders")));
        }

        // --- 处理 tools 部分 ---
        Map<String, Object> tools = asMap(data.get("tools"));
        // 设置 restrictToWorkspace
        config.getTools().setRestrictToWorkspace(booleanValue(
                tools.get("restrictToWorkspace"),
                config.getTools().isRestrictToWorkspace()
        ));
        // 设置 SSRF 白名单
        config.getTools().setSsrfWhitelist(stringList(tools.get("ssrf_whitelist")));
        // 设置 MCP 服务器配置
        config.getTools().setMcpServers(asMap(
                tools.containsKey("mcp_servers") ? tools.get("mcp_servers") : tools.get("mcpServers")
        ));

        // 处理 web 工具配置
        Map<String, Object> web = asMap(tools.get("web"));
        Config.WebToolsConfig wc = config.getTools().getWeb();
        wc.setEnable(booleanValue(web.get("enable"), wc.isEnable()));
        wc.setProxy(string(web.get("proxy"), wc.getProxy()));

        // 处理 web search 配置
        Map<String, Object> webSearch = asMap(web.get("search"));
        Config.WebSearchConfig wsc = wc.getSearch();
        wsc.setProvider(string(webSearch.get("provider"), wsc.getProvider()));
        wsc.setApiKey(string(webSearch.get("api_key"), wsc.getApiKey()));
        wsc.setBaseUrl(string(webSearch.get("base_url"), wsc.getBaseUrl()));
        wsc.setMaxResults(intValue(webSearch.get("max_results"), wsc.getMaxResults()));
        wsc.setTimeout(intValue(webSearch.get("timeout"), wsc.getTimeout()));

        // 处理 exec 工具配置
        Map<String, Object> exec = asMap(tools.get("exec"));
        Config.ExecToolConfig ec = config.getTools().getExec();
        ec.setEnable(booleanValue(exec.get("enable"), ec.isEnable()));
        ec.setTimeout(intValue(exec.get("timeout"), ec.getTimeout()));
        ec.setSandbox(booleanValue(exec.get("sandbox"), ec.isSandbox()));
        ec.setPathAppend(string(exec.get("path_append"), ec.getPathAppend()));
        ec.setAllowedEnvKeys(stringList(exec.get("allowed_env_keys")));

        // --- 处理 channels 部分 ---
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

        Map<String, Object> qq = asMap(channels.get("qq"));
        var qqc = config.getChannels().getQq();
        qqc.setEnabled(booleanValue(qq.get("enabled"), qqc.isEnabled()));
        qqc.setAppId(string(qq.get("app_id"), string(qq.get("appId"), qqc.getAppId())));
        qqc.setSecret(string(qq.get("secret"), qqc.getSecret()));
        qqc.setAllowFrom(stringList(qq.containsKey("allow_from") ? qq.get("allow_from") : qq.get("allowFrom")));
        qqc.setMsgFormat(string(qq.get("msg_format"), string(qq.get("msgFormat"), qqc.getMsgFormat())));
        qqc.setAckMessage(string(qq.get("ack_message"), string(qq.get("ackMessage"), qqc.getAckMessage())));
        qqc.setMediaDir(string(qq.get("media_dir"), string(qq.get("mediaDir"), qqc.getMediaDir())));
        qqc.setDownloadChunkSize(intValue(qq.get("download_chunk_size"), intValue(qq.get("downloadChunkSize"), qqc.getDownloadChunkSize())));
        qqc.setDownloadMaxBytes(longValue(qq.get("download_max_bytes"), longValue(qq.get("downloadMaxBytes"), qqc.getDownloadMaxBytes())));

        Map<String, Object> weixin = asMap(channels.get("weixin"));
        var wc2c = config.getChannels().getWeixin();
        wc2c.setEnabled(booleanValue(weixin.get("enabled"), wc2c.isEnabled()));
        wc2c.setAllowFrom(stringList(weixin.containsKey("allow_from") ? weixin.get("allow_from") : weixin.get("allowFrom")));
        wc2c.setBaseUrl(string(weixin.get("base_url"), string(weixin.get("baseUrl"), wc2c.getBaseUrl())));
        wc2c.setCdnBaseUrl(string(weixin.get("cdn_base_url"), string(weixin.get("cdnBaseUrl"), wc2c.getCdnBaseUrl())));
        wc2c.setRouteTag(string(weixin.get("route_tag"), string(weixin.get("routeTag"), wc2c.getRouteTag())));
        wc2c.setToken(string(weixin.get("token"), wc2c.getToken()));
        wc2c.setStateDir(string(weixin.get("state_dir"), string(weixin.get("stateDir"), wc2c.getStateDir())));
        wc2c.setPollTimeout(intValue(weixin.get("poll_timeout"), intValue(weixin.get("pollTimeout"), wc2c.getPollTimeout())));

        Map<String, Object> websocket = asMap(channels.get("websocket"));
        var wsch = config.getChannels().getWebsocket();
        wsch.setEnabled(booleanValue(websocket.get("enabled"), wsch.isEnabled()));
        wsch.setHost(string(websocket.get("host"), wsch.getHost()));
        wsch.setPort(intValue(websocket.get("port"), wsch.getPort()));
        wsch.setPath(string(websocket.get("path"), wsch.getPath()));
        wsch.setToken(string(websocket.get("token"), wsch.getToken()));
        wsch.setTokenIssuePath(string(websocket.get("token_issue_path"), string(websocket.get("tokenIssuePath"), wsch.getTokenIssuePath())));
        wsch.setTokenIssueSecret(string(websocket.get("token_issue_secret"), string(websocket.get("tokenIssueSecret"), wsch.getTokenIssueSecret())));
        wsch.setTokenTtlS(intValue(websocket.get("token_ttl_s"), intValue(websocket.get("tokenTtlS"), wsch.getTokenTtlS())));
        wsch.setWebsocketRequiresToken(booleanValue(
                websocket.get("websocket_requires_token"),
                booleanValue(websocket.get("websocketRequiresToken"), wsch.isWebsocketRequiresToken())
        ));
        wsch.setAllowFrom(stringList(websocket.containsKey("allow_from") ? websocket.get("allow_from") : websocket.get("allowFrom")));
        wsch.setStreaming(booleanValue(websocket.get("streaming"), wsch.isStreaming()));
        wsch.setMaxMessageBytes(intValue(websocket.get("max_message_bytes"), intValue(websocket.get("maxMessageBytes"), wsch.getMaxMessageBytes())));
        wsch.setPingIntervalS(doubleValue(websocket.get("ping_interval_s"), doubleValue(websocket.get("pingIntervalS"), wsch.getPingIntervalS())));
        wsch.setPingTimeoutS(doubleValue(websocket.get("ping_timeout_s"), doubleValue(websocket.get("pingTimeoutS"), wsch.getPingTimeoutS())));
        wsch.setSslCertfile(string(websocket.get("ssl_certfile"), string(websocket.get("sslCertfile"), wsch.getSslCertfile())));
        wsch.setSslKeyfile(string(websocket.get("ssl_keyfile"), string(websocket.get("sslKeyfile"), wsch.getSslKeyfile())));

        // --- 处理 gateway 部分 ---
        Map<String, Object> gateway = asMap(data.get("gateway"));
        config.getGateway().setPort(intValue(gateway.get("port"), config.getGateway().getPort()));

        // 处理 heartbeat 配置
        Map<String, Object> heartbeat = asMap(gateway.get("heartbeat"));
        Config.HeartbeatConfig hc = config.getGateway().getHeartbeat();
        hc.setEnabled(booleanValue(heartbeat.get("enabled"), hc.isEnabled()));
        hc.setIntervalS(intValue(heartbeat.get("interval_s"), hc.getIntervalS()));
        hc.setKeepRecentMessages(intValue(heartbeat.get("keep_recent_messages"), hc.getKeepRecentMessages()));

        // --- 处理 api 部分 ---
        Map<String, Object> api = asMap(data.get("api"));
        config.getApi().setHost(string(api.get("host"), config.getApi().getHost()));
        config.getApi().setPort(intValue(api.get("port"), config.getApi().getPort()));
        config.getApi().setTimeout(doubleValue(api.get("timeout"), config.getApi().getTimeout()));
        config.getApi().setBearerToken(string(api.get("bearer_token"), string(api.get("bearerToken"), config.getApi().getBearerToken())));

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
        defaults.put("disabled_skills", ad.getDisabledSkills());
        defaults.put("session_ttl_minutes", ad.getSessionTtlMinutes());

        // 构建 dream 配置
        Map<String, Object> dream = new LinkedHashMap<>();
        dream.put("enabled", ad.getDream().isEnabled());
        dream.put("model_override", ad.getDream().getModelOverride());
        dream.put("max_batch_size", ad.getDream().getMaxBatchSize());
        dream.put("max_iterations", ad.getDream().getMaxIterations());
        dream.put("cron", ad.getDream().getCron());
        defaults.put("dream", dream);

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

        // --- 构建 tools 部分 ---
        Map<String, Object> tools = new LinkedHashMap<>();
        
        // 构建 web 配置
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("enable", config.getTools().getWeb().isEnable());
        web.put("proxy", config.getTools().getWeb().getProxy());
        
        // 构建 web search 配置
        Map<String, Object> webSearch = new LinkedHashMap<>();
        webSearch.put("provider", config.getTools().getWeb().getSearch().getProvider());
        webSearch.put("api_key", config.getTools().getWeb().getSearch().getApiKey());
        webSearch.put("base_url", config.getTools().getWeb().getSearch().getBaseUrl());
        webSearch.put("max_results", config.getTools().getWeb().getSearch().getMaxResults());
        webSearch.put("timeout", config.getTools().getWeb().getSearch().getTimeout());
        web.put("search", webSearch);
        tools.put("web", web);

        // 构建 exec 配置
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("enable", config.getTools().getExec().isEnable());
        exec.put("timeout", config.getTools().getExec().getTimeout());
        exec.put("sandbox", config.getTools().getExec().isSandbox());
        exec.put("path_append", config.getTools().getExec().getPathAppend());
        exec.put("allowed_env_keys", config.getTools().getExec().getAllowedEnvKeys());
        tools.put("exec", exec);

        // 设置 tools 的其他属性
        tools.put("restrictToWorkspace", config.getTools().isRestrictToWorkspace());
        tools.put("ssrf_whitelist", config.getTools().getSsrfWhitelist());
        tools.put("mcp_servers", config.getTools().getMcpServers());
        root.put("tools", tools);

        // --- 构建 channels 部分 ---
        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("send_progress", config.getChannels().isSendProgress());
        channels.put("send_tool_hints", config.getChannels().isSendToolHints());
        channels.put("transcription_provider", config.getChannels().getTranscriptionProvider());

        Map<String, Object> qq = new LinkedHashMap<>();
        qq.put("enabled", config.getChannels().getQq().isEnabled());
        qq.put("app_id", config.getChannels().getQq().getAppId());
        qq.put("secret", config.getChannels().getQq().getSecret());
        qq.put("allow_from", config.getChannels().getQq().getAllowFrom());
        qq.put("msg_format", config.getChannels().getQq().getMsgFormat());
        qq.put("ack_message", config.getChannels().getQq().getAckMessage());
        qq.put("media_dir", config.getChannels().getQq().getMediaDir());
        qq.put("download_chunk_size", config.getChannels().getQq().getDownloadChunkSize());
        qq.put("download_max_bytes", config.getChannels().getQq().getDownloadMaxBytes());
        channels.put("qq", qq);

        Map<String, Object> weixin = new LinkedHashMap<>();
        weixin.put("enabled", config.getChannels().getWeixin().isEnabled());
        weixin.put("allow_from", config.getChannels().getWeixin().getAllowFrom());
        weixin.put("base_url", config.getChannels().getWeixin().getBaseUrl());
        weixin.put("cdn_base_url", config.getChannels().getWeixin().getCdnBaseUrl());
        weixin.put("route_tag", config.getChannels().getWeixin().getRouteTag());
        weixin.put("token", config.getChannels().getWeixin().getToken());
        weixin.put("state_dir", config.getChannels().getWeixin().getStateDir());
        weixin.put("poll_timeout", config.getChannels().getWeixin().getPollTimeout());
        channels.put("weixin", weixin);

        Map<String, Object> websocket = new LinkedHashMap<>();
        websocket.put("enabled", config.getChannels().getWebsocket().isEnabled());
        websocket.put("host", config.getChannels().getWebsocket().getHost());
        websocket.put("port", config.getChannels().getWebsocket().getPort());
        websocket.put("path", config.getChannels().getWebsocket().getPath());
        websocket.put("token", config.getChannels().getWebsocket().getToken());
        websocket.put("token_issue_path", config.getChannels().getWebsocket().getTokenIssuePath());
        websocket.put("token_issue_secret", config.getChannels().getWebsocket().getTokenIssueSecret());
        websocket.put("token_ttl_s", config.getChannels().getWebsocket().getTokenTtlS());
        websocket.put("websocket_requires_token", config.getChannels().getWebsocket().isWebsocketRequiresToken());
        websocket.put("allow_from", config.getChannels().getWebsocket().getAllowFrom());
        websocket.put("streaming", config.getChannels().getWebsocket().isStreaming());
        websocket.put("max_message_bytes", config.getChannels().getWebsocket().getMaxMessageBytes());
        websocket.put("ping_interval_s", config.getChannels().getWebsocket().getPingIntervalS());
        websocket.put("ping_timeout_s", config.getChannels().getWebsocket().getPingTimeoutS());
        websocket.put("ssl_certfile", config.getChannels().getWebsocket().getSslCertfile());
        websocket.put("ssl_keyfile", config.getChannels().getWebsocket().getSslKeyfile());
        channels.put("websocket", websocket);

        root.put("channels", channels);

        // --- 构建 gateway 部分 ---
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("port", config.getGateway().getPort());
        
        // 构建 heartbeat 配置
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("enabled", config.getGateway().getHeartbeat().isEnabled());
        heartbeat.put("interval_s", config.getGateway().getHeartbeat().getIntervalS());
        heartbeat.put("keep_recent_messages", config.getGateway().getHeartbeat().getKeepRecentMessages());
        gateway.put("heartbeat", heartbeat);
        root.put("gateway", gateway);

        // --- 构建 api 部分 ---
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("host", config.getApi().getHost());
        api.put("port", config.getApi().getPort());
        api.put("timeout", config.getApi().getTimeout());
        api.put("bearer_token", config.getApi().getBearerToken());
        root.put("api", api);

        return root;
    }

    // helpers
    // 辅助方法区域

    /**
     * 安全地将对象转换为 String-Object Map。
     * 如果对象不是 Map，则返回空的 LinkedHashMap。
     *
     * @param o 待转换的对象
     * @return Map<String, Object>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    /**
     * 安全地将对象转换为 String-String Map。
     *
     * @param o 待转换的对象
     * @return Map<String, String>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
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
    @SuppressWarnings("unchecked")
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

    private static long longValue(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        try {
            return o != null ? Long.parseLong(String.valueOf(o)) : def;
        } catch (Exception e) {
            return def;
        }
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
