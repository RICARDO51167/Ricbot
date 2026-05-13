package ricbot.tool.web;

import ricbot.tool.api.Tool;
import ricbot.infra.config.Config;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 对应 Python: WebSearchTool
 *
 * 主要目标：
 * 1. 按 provider 分发搜索请求
 * 2. 返回标题/链接/摘要
 * 3. SearXNG 先做 URL 校验
 * 4. 某些 provider 未配置时回退 DuckDuckGo
 */
public class WebSearchTool extends Tool {

    // 搜索配置对象，包含提供商选择、API Key 等配置
    private final Config.WebSearchConfig config;
    // 代理地址，目前代码中未直接使用，可能通过 WebToolSupport 间接使用
    private final String proxy;

    /**
     * 构造函数
     * @param config 网页搜索配置，如果为 null 则使用默认配置
     * @param proxy 代理地址
     */
    public WebSearchTool(Config.WebSearchConfig config, String proxy) {
        // 如果传入的配置为空，则创建一个空的默认配置对象，避免空指针异常
        this.config = config != null ? config : new Config.WebSearchConfig();
        this.proxy = proxy;
    }

    /**
     * 获取工具名称
     * @return 工具名称 "web_search"
     */
    @Override
    public String getName() {
        return "web_search";
    }

    /**
     * 获取工具描述
     * @return 工具的功能描述字符串
     */
    @Override
    public String getDescription() {
        return "网页搜索。返回标题、URL 与摘要片段。count 默认 5（最大 10）。需要阅读全文时用 web_fetch。";
    }

    /**
     * 标识该工具是否为只读操作
     * @return true，表示该工具不会修改任何数据
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 执行搜索操作的主入口方法
     * @param query 搜索关键词
     * @param count 期望返回的结果数量
     * @return 格式化后的搜索结果字符串或错误信息
     */
    public String execute(String query, Integer count) {
        // 确定使用的搜索提供商，优先使用配置中的 provider，默认为 duckduckgo，并转换为小写
        String provider = config.getProvider() != null ? config.getProvider().trim().toLowerCase(Locale.ROOT) : "duckduckgo";
        // 计算最终请求的结果数量，范围限制在 1 到 10 之间
        // 如果 count 为 null，则使用配置中的 maxResults
        int n = Math.min(Math.max(count != null ? count : config.getMaxResults(), 1), 10);

        // 根据提供商类型分发到不同的搜索实现方法
        return switch (provider) {
            case "duckduckgo" -> searchDuckDuckGo(query, n);
            case "tavily" -> searchTavily(query, n);
            case "searxng" -> searchSearxng(query, n);
            case "jina" -> searchJina(query, n);
            case "brave" -> searchBrave(query, n);
            case "kagi" -> searchKagi(query, n);
            // 如果提供商未知，返回错误信息
            default -> "错误：未知的搜索提供商：'" + provider + "'";
        };
    }

    /**
     * 使用 Brave Search API 进行搜索
     * @param query 搜索关键词
     * @param n 结果数量
     * @return 格式化后的搜索结果或错误信息
     */
    private String searchBrave(String query, int n) {
        // 获取 API Key，优先从配置中获取，其次从环境变量 BRAVE_API_KEY 获取
        String apiKey = firstNonBlank(config.getApiKey(), System.getenv("BRAVE_API_KEY"));
        // 如果 API Key 为空，则回退到 DuckDuckGo 搜索
        if (isBlank(apiKey)) {
            return searchDuckDuckGo(query, n);
        }

        try {
            // 构建 HTTP 客户端
            HttpClient client = WebToolSupport.buildClient(proxy);
            // 构造 Brave Search API 的请求 URL，包含查询词和结果数量
            String url = "https://api.search.brave.com/res/v1/web/search?q="
                    + encode(query) + "&count=" + n;

            // 发送 GET 请求获取 JSON 响应
            Map<String, Object> json = WebToolSupport.getJson(
                    client,
                    url,
                    // 设置请求头，包括 Accept 和订阅 Token
                    Map.of(
                            "Accept", "application/json",
                            "X-Subscription-Token", apiKey
                    ),
                    Duration.ofSeconds(10) // 设置超时时间为 10 秒
            );

            // 初始化结果列表
            List<Map<String, Object>> items = new ArrayList<>();
            // 解析 JSON 中的 "web" 字段
            Map<String, Object> web = asMap(json.get("web"));
            // 获取 "web" 下的 "results" 列表
            Object resultsObj = web.get("results");
            // 如果 results 是列表类型，则遍历处理
            if (resultsObj instanceof List<?> list) {
                for (Object itemObj : list) {
                    // 将每个结果项转换为 Map
                    Map<String, Object> item = asMap(itemObj);
                    // 提取 title, url, description 并添加到结果列表
                    items.add(Map.of(
                            "title", safe(item.get("title")),
                            "url", safe(item.get("url")),
                            "content", safe(item.get("description"))
                    ));
                }
            }

            // 格式化并返回结果
            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            // 捕获异常并返回错误信息
            return "错误：" + e.getMessage();
        }
    }

    /**
     * 使用 Tavily API 进行搜索
     * @param query 搜索关键词
     * @param n 结果数量
     * @return 格式化后的搜索结果或错误信息
     */
    private String searchTavily(String query, int n) {
        // 获取 API Key，优先从配置中获取，其次从环境变量 TAVILY_API_KEY 获取
        String apiKey = firstNonBlank(config.getApiKey(), System.getenv("TAVILY_API_KEY"));
        // 如果 API Key 为空，则回退到 DuckDuckGo 搜索
        if (isBlank(apiKey)) {
            return searchDuckDuckGo(query, n);
        }

        try {
            // 构建 HTTP 客户端
            HttpClient client = WebToolSupport.buildClient(proxy);
            // 发送 POST 请求到 Tavily API
            Map<String, Object> json = WebToolSupport.postJson(
                    client,
                    "https://api.tavily.com/search",
                    // 设置 Authorization 请求头
                    Map.of("Authorization", "Bearer " + apiKey),
                    // 设置请求体，包含查询词和最大结果数
                    Map.of(
                            "query", query,
                            "max_results", n
                    ),
                    Duration.ofSeconds(15) // 设置超时时间为 15 秒
            );

            // 解析结果列表
            List<Map<String, Object>> items = asListOfMaps(json.get("results"));
            // 格式化并返回结果
            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            // 捕获异常并返回错误信息
            return "错误：" + e.getMessage();
        }
    }

    /**
     * 使用 SearXNG 实例进行搜索
     * @param query 搜索关键词
     * @param n 结果数量
     * @return 格式化后的搜索结果或错误信息
     */
    private String searchSearxng(String query, int n) {
        // 获取 SearXNG 基础 URL，优先从配置中获取，其次从环境变量 SEARXNG_BASE_URL 获取
        String baseUrl = firstNonBlank(config.getBaseUrl(), System.getenv("SEARXNG_BASE_URL"));
        // 如果基础 URL 为空，则回退到 DuckDuckGo 搜索
        if (isBlank(baseUrl)) {
            return searchDuckDuckGo(query, n);
        }

        // 去除基础 URL 末尾的斜杠，并拼接 /search 路径
        String endpoint = baseUrl.replaceAll("/+$", "") + "/search";
        // 校验 URL 的安全性，防止 SSRF 等攻击
        var valid = WebToolSupport.validateUrlSafe(endpoint);
        // 如果 URL 无效，返回错误信息
        if (!valid.ok()) {
            return "错误：SearXNG URL 无效：" + valid.message();
        }

        try {
            // 构建 HTTP 客户端
            HttpClient client = WebToolSupport.buildClient(proxy);
            // 构造完整的请求 URL，包含查询词和 JSON 格式参数
            String url = endpoint + "?q=" + encode(query) + "&format=json";

            // 发送 GET 请求获取 JSON 响应
            Map<String, Object> json = WebToolSupport.getJson(
                    client,
                    url,
                    // 设置 User-Agent 请求头
                    Map.of("User-Agent", WebToolSupport.USER_AGENT),
                    Duration.ofSeconds(10) // 设置超时时间为 10 秒
            );

            // 解析结果列表
            List<Map<String, Object>> items = asListOfMaps(json.get("results"));
            // 格式化并返回结果
            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            // 捕获异常并返回错误信息
            return "错误：" + e.getMessage();
        }
    }

    /**
     * 使用 Jina AI Search API 进行搜索
     * @param query 搜索关键词
     * @param n 结果数量
     * @return 格式化后的搜索结果或错误信息
     */
    private String searchJina(String query, int n) {
        // 获取 API Key，优先从配置中获取，其次从环境变量 JINA_API_KEY 获取
        String apiKey = firstNonBlank(config.getApiKey(), System.getenv("JINA_API_KEY"));
        // 如果 API Key 为空，则回退到 DuckDuckGo 搜索
        if (isBlank(apiKey)) {
            return searchDuckDuckGo(query, n);
        }

        try {
            // 构建 HTTP 客户端
            HttpClient client = WebToolSupport.buildClient(proxy);
            // 构造 Jina Search API 的请求 URL
            String url = "https://s.jina.ai/?q=" + encode(query);

            // 发送 GET 请求获取 JSON 响应
            Map<String, Object> json = WebToolSupport.getJson(
                    client,
                    url,
                    // 设置请求头，包括 Accept 和 Authorization
                    Map.of(
                            "Accept", "application/json",
                            "Authorization", "Bearer " + apiKey
                    ),
                    Duration.ofSeconds(10) // 设置超时时间为 10 秒
            );

            // 初始化结果列表
            List<Map<String, Object>> items = new ArrayList<>();
            // 解析 JSON 中的 "data" 字段
            Object dataObj = json.get("data");
            // 如果 data 是列表类型，则遍历处理
            if (dataObj instanceof List<?> list) {
                for (Object obj : list) {
                    // 将每个数据项转换为 Map
                    Map<String, Object> d = asMap(obj);
                    // 提取 title, url, content，并对 content 进行截断处理（最多 500 字符）
                    items.add(Map.of(
                            "title", safe(d.get("title")),
                            "url", safe(d.get("url")),
                            "content", truncate(safe(d.get("content")), 500)
                    ));
                }
            }

            // 格式化并返回结果
            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            // 如果发生异常，回退到 DuckDuckGo 搜索
            return searchDuckDuckGo(query, n);
        }
    }

    /**
     * 使用 Kagi Search API 进行搜索
     * @param query 搜索关键词
     * @param n 结果数量
     * @return 格式化后的搜索结果或错误信息
     */
    private String searchKagi(String query, int n) {
        // 获取 API Key，优先从配置中获取，其次从环境变量 KAGI_API_KEY 获取
        String apiKey = firstNonBlank(config.getApiKey(), System.getenv("KAGI_API_KEY"));
        // 如果 API Key 为空，则回退到 DuckDuckGo 搜索
        if (isBlank(apiKey)) {
            return searchDuckDuckGo(query, n);
        }

        try {
            // 构建 HTTP 客户端
            HttpClient client = WebToolSupport.buildClient(proxy);
            // 构造 Kagi Search API 的请求 URL，包含查询词和限制数量
            String url = "https://kagi.com/api/v0/search?q=" + encode(query) + "&limit=" + n;

            // 发送 GET 请求获取 JSON 响应
            Map<String, Object> json = WebToolSupport.getJson(
                    client,
                    url,
                    // 设置 Authorization 请求头，前缀为 "Bot "
                    Map.of("Authorization", "Bot " + apiKey),
                    Duration.ofSeconds(10) // 设置超时时间为 10 秒
            );

            // 初始化结果列表
            List<Map<String, Object>> items = new ArrayList<>();
            // 解析 JSON 中的 "data" 字段
            Object dataObj = json.get("data");
            // 如果 data 是列表类型，则遍历处理
            if (dataObj instanceof List<?> list) {
                for (Object obj : list) {
                    // 将每个数据项转换为 Map
                    Map<String, Object> d = asMap(obj);
                    // 获取类型字段 "t"，如果是数字则转为 int，否则默认为 -1
                    int t = d.get("t") instanceof Number num ? num.intValue() : -1;
                    // 只处理类型为 0 的结果（通常表示普通搜索结果）
                    if (t == 0) {
                        // 提取 title, url, snippet 并添加到结果列表
                        items.add(Map.of(
                                "title", safe(d.get("title")),
                                "url", safe(d.get("url")),
                                "content", safe(d.get("snippet"))
                        ));
                    }
                }
            }

            // 格式化并返回结果
            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            // 捕获异常并返回错误信息
            return "错误：" + e.getMessage();
        }
    }

    /**
     * 使用 DuckDuckGo HTML 页面进行搜索（兜底方案）
     * Java 标准库里没有 DDG 官方 SDK，这里给你一个可运行兜底版：
     * 直接调 DuckDuckGo HTML 接口并做简单抽取。
     *
     * 后面你如果想完全对齐 Python DDGS，再单独换实现。
     * @param query 搜索关键词
     * @param n 结果数量（此实现中固定返回 1 条聚合结果）
     * @return 格式化后的搜索结果或错误信息
     */
    private String searchDuckDuckGo(String query, int n) {
        try {
            // 构建 HTTP 客户端
            HttpClient client = WebToolSupport.buildClient(proxy);
            // 构造 DuckDuckGo HTML 搜索 URL
            String url = "https://duckduckgo.com/html/?q=" + encode(query);

            // 获取 HTML 内容，超时时间优先使用配置中的 timeout，否则默认 10 秒
            String html = WebToolSupport.fetchText(
                    client,
                    url,
                    Duration.ofSeconds(config.getTimeout() > 0 ? config.getTimeout() : 10)
            );

            // 轻量抽取：这里先直接返回简化文本，避免无依赖情况下卡住。
            // 你后面若想增强，可再用 Jsoup 替换。
            // 从 HTML 中提取可读文本，最大长度 3000
            String text = WebToolSupport.extractReadable(html, "text", 3000);
            // 如果提取的文本为空，返回未找到结果提示
            if (text.isBlank()) {
                return "未找到结果：" + query;
            }

            // 初始化结果列表
            List<Map<String, Object>> items = new ArrayList<>();
            // 将提取的文本作为单条结果添加，标题固定，URL 指向 DDG 搜索结果页
            items.add(Map.of(
                    "title", "DuckDuckGo 搜索结果",
                    "url", "https://duckduckgo.com/?q=" + encode(query),
                    "content", truncate(text, 800) // 内容截断至 800 字符
            ));
            // 格式化并返回结果，数量固定为 1
            return WebToolSupport.formatResults(query, items, 1);
        } catch (Exception e) {
            // 捕获异常并返回错误信息
            return "错误：DuckDuckGo 搜索失败（" + e.getMessage() + "）";
        }
    }

    /**
     * URL 编码辅助方法
     * @param s 待编码字符串
     * @return UTF-8 编码后的字符串
     */
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 将对象安全地转换为 Map<String, Object>
     * @param obj 待转换对象
     * @return 转换后的 Map，如果转换失败则返回空 LinkedHashMap
     */
    private static Map<String, Object> asMap(Object obj) {
        return ricbot.infra.common.JsonMapUtils.asObjectMap(obj);
    }

    /**
     * 将对象安全地转换为 List<Map<String, Object>>
     * @param obj 待转换对象
     * @return 转换后的 List，包含多个 Map
     */
    private static List<Map<String, Object>> asListOfMaps(Object obj) {
        // 初始化结果列表
        List<Map<String, Object>> list = new ArrayList<>();
        // 如果对象是 List 类型，则遍历处理
        if (obj instanceof List<?> raw) {
            for (Object item : raw) {
                // 如果列表项是 Map 类型，则转换后添加到结果列表
                if (item instanceof Map<?, ?> m) {
                    list.add(asMap(m));
                }
            }
        }
        return list;
    }

    /**
     * 安全地将对象转换为字符串，处理 null 值
     * @param o 待转换对象
     * @return 字符串表示，如果对象为 null 则返回空字符串
     */
    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 判断字符串是否为空或空白
     * @param s 待检查字符串
     * @return 如果字符串为 null 或只包含空白字符则返回 true
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 获取第一个非空白的字符串
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 如果 a 非空白则返回 a，否则返回 b
     */
    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    /**
     * 截断字符串到指定长度
     * @param s 待截断字符串
     * @param max 最大长度
     * @return 截断后的字符串
     */
    private static String truncate(String s, int max) {
        // 如果字符串为 null，返回空字符串
        if (s == null) {
            return "";
        }
        // 如果长度小于等于最大值，直接返回；否则截取前 max 个字符
        return s.length() <= max ? s : s.substring(0, max);
    }
}
