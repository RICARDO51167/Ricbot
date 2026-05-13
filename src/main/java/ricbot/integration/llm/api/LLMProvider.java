package ricbot.integration.llm.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * 对应 Python: LLMProvider
 *
 * 主要目标：
 * 1. 定义 provider 抽象基类
 * 2. 提供共享的 request / response 清洗与错误处理工具
 * 3. 提供统一 retry 执行框架
 */
public abstract class LLMProvider {

    // JSON 对象映射器，用于序列化和反序列化
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };

    // 聊天重试的延迟时间列表（秒），分别为第1、2、3次重试的等待时间
    protected static final List<Integer> CHAT_RETRY_DELAYS = List.of(1, 2, 4);

    // 可重试的 HTTP 状态码集合：408 (请求超时), 409 (冲突), 429 (请求过多)
    protected static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 409, 429);
    // 瞬态错误类型集合，通常表示网络或临时性问题
    protected static final Set<String> TRANSIENT_ERROR_KINDS = Set.of("timeout", "connection");

    // 不可重试的 429 错误令牌集合，通常与配额或账单问题相关
    protected static final Set<String> NON_RETRYABLE_429_ERROR_TOKENS = Set.of(
            "insufficient_quota",       // 配额不足
            "quota_exceeded",           // 超出配额
            "quota_exhausted",          // 配额耗尽
            "billing_hard_limit_reached", // 达到账单硬性限制
            "insufficient_balance",     // 余额不足
            "credit_balance_too_low",   // 信用额度过低
            "billing_not_active",       // 账单未激活
            "payment_required"          // 需要付款
    );

    // 可重试的 429 错误令牌集合，通常与速率限制相关
    protected static final Set<String> RETRYABLE_429_ERROR_TOKENS = Set.of(
            "rate_limit_exceeded",      // 超出速率限制
            "rate_limit_error",         // 速率限制错误
            "too_many_requests",        // 请求过多
            "request_limit_exceeded",   // 超出请求限制
            "requests_limit_exceeded",  // 超出请求数量限制
            "overloaded_error"          // 过载错误
    );

    // 不可重试的 429 错误文本标记，用于在响应内容中匹配
    protected static final List<String> NON_RETRYABLE_429_TEXT_MARKERS = List.of(
            "insufficient_quota",
            "insufficient quota",
            "quota exceeded",
            "quota exhausted",
            "billing hard limit",
            "billing_hard_limit_reached",
            "billing not active",
            "insufficient balance",
            "insufficient_balance",
            "credit balance too low",
            "payment required",
            "out of credits",
            "out of quota",
            "exceeded your current quota"
    );

    // 可重试的 429 错误文本标记，用于在响应内容中匹配
    protected static final List<String> RETRYABLE_429_TEXT_MARKERS = List.of(
            "rate limit",
            "rate_limit",
            "too many requests",
            "retry after",
            "try again in",
            "temporarily unavailable",
            "overloaded",
            "concurrency limit"
    );

    // API 密钥
    protected String apiKey;
    // API 基础 URL
    protected String apiBase;
    // 默认模型名称
    protected String defaultModel;
    // 生成设置
    protected GenerationSettings generation = new GenerationSettings();

    // 无参构造函数
    public LLMProvider() {
    }

    // 带参数的构造函数
    public LLMProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
    }

    // 获取 API 密钥
    public String getApiKey() {
        return apiKey;
    }

    // 设置 API 密钥
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    // 获取 API 基础 URL
    public String getApiBase() {
        return apiBase;
    }

    // 设置 API 基础 URL
    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    // 获取默认模型
    public String getDefaultModel() {
        return defaultModel;
    }

    // 设置默认模型
    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    /**
     * 对应 Python abstractmethod: chat(...)
     * 执行聊天请求的核心抽象方法
     */
    public abstract LLMResponse chat(
            List<Map<String, Object>> messages,   // 消息列表
            List<Map<String, Object>> tools,      // 工具列表
            String model,                         // 模型名称
            Integer maxTokens,                    // 最大 token 数
            Double temperature,                   // 温度参数
            String reasoningEffort,               // 推理努力程度
            Object toolChoice                     // 工具选择策略
    ) throws Exception;

    /**
     * 执行聊天请求，带自动重试。
     */
    public LLMResponse chatWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model
    ) throws Exception {
        return runWithRetry(() -> chat(messages, tools, model, null, null, null, null));
    }

    /**
     * 对应 Python chat_stream(...)
     *
     * 默认实现：不支持流式，走一次普通 chat，然后把完整内容一次性推给 onDelta。
     */
    public LLMResponse chatStream(
            List<Map<String, Object>> messages,   // 消息列表
            List<Map<String, Object>> tools,      // 工具列表
            String model,                         // 模型名称
            Integer maxTokens,                    // 最大 token 数
            Double temperature,                   // 温度参数
            String reasoningEffort,               // 推理努力程度
            Object toolChoice,                    // 工具选择策略
            StreamDeltaHandler onDelta,           // 增量回调 handler
            StreamEndHandler onEnd                // 结束回调 handler
    ) throws Exception {
        // 调用普通的 chat 方法获取完整响应
        LLMResponse response = chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice);
        // 如果响应内容不为空且增量回调 handler 存在，则触发增量回调
        if (response.getContent() != null && onDelta != null) {
            onDelta.handle(response.getContent());
        }
        // 如果结束回调 handler 存在，则触发结束回调
        if (onEnd != null) {
            onEnd.handle(response);
        }
        return response;
    }

    /**
     * 对应 Python: _sanitize_empty_content(messages)
     * 清洗消息列表中的空内容
     */
    public static List<Map<String, Object>> sanitizeEmptyContent(List<Map<String, Object>> messages) {
        // 初始化结果列表
        List<Map<String, Object>> result = new ArrayList<>();
        // 如果输入消息列表为空，直接返回空结果列表
        if (messages == null) {
            return result;
        }

        // 遍历每条消息
        for (Map<String, Object> msg : messages) {
            // 创建消息的浅拷贝
            Map<String, Object> clean = new LinkedHashMap<>(msg);
            // 获取内容字段
            Object content = msg.get("content");
            // 获取角色字段
            Object role = msg.get("role");

            // 如果内容是空字符串
            if (content instanceof String s && s.isEmpty()) {
                // 如果是 assistant 角色且有工具调用，则将内容设为 null
                if ("assistant".equals(role) && msg.get("tool_calls") != null) {
                    clean.put("content", null);
                } else {
                    // 否则将内容设为 "(空)"
                    clean.put("content", "(空)");
                }
                // 添加清洗后的消息并继续下一条
                result.add(clean);
                continue;
            }

            // 如果内容是列表类型
            if (content instanceof List<?> list) {
                // 初始化新的内容项列表
                List<Object> newItems = new ArrayList<>();
                // 标记内容是否发生变化
                boolean changed = false;

                // 遍历列表中的每一项
                for (Object item : list) {
                    // 如果项是 Map 类型
                    if (item instanceof Map<?, ?> raw) {
                        // 创建新的 Map 并复制原始数据
                        Map<String, Object> dict = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : raw.entrySet()) {
                            if (e.getKey() != null) {
                                dict.put(String.valueOf(e.getKey()), e.getValue());
                            }
                        }

                        // 获取类型字段
                        Object type = dict.get("type");
                        // 如果是文本类型但没有 text 字段，则标记为已更改并跳过该项
                        if ((Objects.equals(type, "text")
                                || Objects.equals(type, "input_text")
                                || Objects.equals(type, "output_text"))
                                && !dict.containsKey("text")) {
                            changed = true;
                            continue;
                        }

                        // 如果包含 _meta 字段，则移除并标记为已更改
                        if (dict.containsKey("_meta")) {
                            dict.remove("_meta");
                            changed = true;
                        }
                        // 添加处理后的项
                        newItems.add(dict);
                    } else {
                        // 非 Map 类型直接添加
                        newItems.add(item);
                    }
                }

                // 如果内容发生了变化
                if (changed) {
                    // 如果新列表不为空，则更新内容
                    if (!newItems.isEmpty()) {
                        clean.put("content", newItems);
                    } else if ("assistant".equals(role) && msg.get("tool_calls") != null) {
                        // 如果是 assistant 且有工具调用，内容设为 null
                        clean.put("content", null);
                    } else {
                        // 否则内容设为 "(空)"
                        clean.put("content", "(空)");
                    }
                }
                // 添加清洗后的消息并继续下一条
                result.add(clean);
                continue;
            }

            // 如果内容是 Map 类型，将其转换为单元素列表
            if (content instanceof Map<?, ?> map) {
                clean.put("content", List.of(new LinkedHashMap<>(map)));
                result.add(clean);
                continue;
            }

            // 其他情况直接添加原始消息副本
            result.add(clean);
        }

        return result;
    }

    /**
     * 对应 Python: _tool_name(tool)
     * 从工具定义中提取工具名称
     */
    public static String toolName(Map<String, Object> tool) {
        // 尝试直接从 name 字段获取
        Object name = tool.get("name");
        if (name instanceof String s) {
            return s;
        }
        // 尝试从 function.name 字段获取
        Object fn = tool.get("function");
        Map<String, Object> function = asObjectMap(fn);
        if (function != null) {
            Object fname = function.get("name");
            if (fname instanceof String s) {
                return s;
            }
        }
        // 默认返回空字符串
        return "";
    }

    /**
     * 对应 Python: _extract_error_type_code(payload)
     * 从负载中提取错误类型和代码
     */
    public static String[] extractErrorTypeCode(Object payload) {
        Map<String, Object> data = null;

        // 如果负载是 Map 类型，直接使用
        if (payload instanceof Map<?, ?>) {
            data = asObjectMap(payload);
        } else if (payload instanceof String text && !text.isBlank()) {
            // 如果负载是字符串，尝试解析为 JSON
            try {
                data = MAPPER.readValue(text, JSON_OBJECT_TYPE);
            } catch (Exception ignored) {
                // 解析失败忽略
            }
        }

        // 如果无法获取数据，返回 null 数组
        if (data == null) {
            return new String[]{null, null};
        }

        // 获取顶层的 error, type, code 字段
        Object errorObj = data.get("error");
        Object typeValue = data.get("type");
        Object codeValue = data.get("code");

        // 如果存在 error 对象，优先从中提取 type 和 code
        Map<String, Object> error = asObjectMap(errorObj);
        if (error != null) {
            Object t = error.get("type");
            Object c = error.get("code");
            if (t != null) typeValue = t;
            if (c != null) codeValue = c;
        }

        // 规范化并返回错误类型和代码
        return new String[]{normalizeErrorToken(typeValue), normalizeErrorToken(codeValue)};
    }

    private static Map<String, Object> asObjectMap(Object value) {
        return ricbot.infra.common.JsonMapUtils.asNullableObjectMap(value);
    }

    /**
     * 规范化错误令牌
     */
    public static String normalizeErrorToken(Object value) {
        if (value == null) {
            return null;
        }
        // 转换为小写字符串并去除空白
        String token = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        // 如果为空则返回 null
        return token.isBlank() ? null : token;
    }

    /**
     * 对应 Python: _is_retryable_429_response(response)
     * 判断 429 响应是否可重试
     */
    public static boolean isRetryable429Response(LLMResponse response) {
        // 获取规范化的错误类型和代码令牌
        String typeToken = normalizeErrorToken(response.getErrorType());
        String codeToken = normalizeErrorToken(response.getErrorCode());

        // 收集语义令牌
        Set<String> semanticTokens = new HashSet<>();
        if (typeToken != null) semanticTokens.add(typeToken);
        if (codeToken != null) semanticTokens.add(codeToken);

        // 检查是否包含不可重试的错误令牌
        for (String token : semanticTokens) {
            if (NON_RETRYABLE_429_ERROR_TOKENS.contains(token)) {
                return false;
            }
        }

        // 获取响应内容并转为小写
        String content = response.getContent() != null
                ? response.getContent().toLowerCase(Locale.ROOT)
                : "";

        // 检查是否包含不可重试的文本标记
        for (String marker : NON_RETRYABLE_429_TEXT_MARKERS) {
            if (content.contains(marker)) {
                return false;
            }
        }

        // 检查是否包含可重试的错误令牌
        for (String token : semanticTokens) {
            if (RETRYABLE_429_ERROR_TOKENS.contains(token)) {
                return true;
            }
        }

        // 检查是否包含可重试的文本标记
        for (String marker : RETRYABLE_429_TEXT_MARKERS) {
            if (content.contains(marker)) {
                return true;
            }
        }

        // 默认认为可重试
        return true;
    }

    /**
     * 对应 Python: _is_transient_response(response)
     * 判断响应是否为瞬态错误（可重试）
     */
    public static boolean isTransientResponse(LLMResponse response) {
        // 如果显式设置了是否应重试，直接返回该值
        if (response.getErrorShouldRetry() != null) {
            return Boolean.TRUE.equals(response.getErrorShouldRetry());
        }

        // 根据状态码判断
        if (response.getErrorStatusCode() != null) {
            int status = response.getErrorStatusCode();
            // 429 状态码需要进一步判断
            if (status == 429) {
                return isRetryable429Response(response);
            }
            // 其他可重试状态码或服务器错误
            if (RETRYABLE_STATUS_CODES.contains(status) || status >= 500) {
                return true;
            }
        }

        // 根据错误类型判断
        String kind = response.getErrorKind() != null
                ? response.getErrorKind().trim().toLowerCase(Locale.ROOT)
                : "";

        if (TRANSIENT_ERROR_KINDS.contains(kind)) {
            return true;
        }

        // 根据响应内容判断
        String content = response.getContent() != null
                ? response.getContent().toLowerCase(Locale.ROOT)
                : "";

        return content.contains("429")
                || content.contains("rate limit")
                || content.contains("500")
                || content.contains("502")
                || content.contains("503")
                || content.contains("504")
                || content.contains("overloaded")
                || content.contains("timeout")
                || content.contains("timed out")
                || content.contains("connection")
                || content.contains("server error")
                || content.contains("temporarily unavailable");
    }

    /**
     * 对应 Python: _extract_retry_after_from_headers(headers)
     * 从 HTTP 头中提取 Retry-After 值
     */
    public static Double extractRetryAfterFromHeaders(Map<String, ?> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }

        // 尝试获取 retry-after 头，不区分大小写
        Object value = headers.get("retry-after");
        if (value == null) value = headers.get("Retry-After");
        if (value == null) return null;

        String s = String.valueOf(value).trim();
        if (s.isBlank()) return null;

        // 尝试解析为数字（秒）
        try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
        }

        // 尝试解析为 RFC 1123 日期格式
        try {
            ZonedDateTime dt = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = (dt.toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000;
            return Math.max(0, (double) seconds);
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * 对应 Python: _extract_retry_after(text)
     * 从文本中提取重试等待时间
     */
    public static Double extractRetryAfter(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String lower = text.toLowerCase(Locale.ROOT);

        // 匹配 "retry after <seconds>"
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("retry after\\s*(\\d+(?:\\.\\d+)?)").matcher(lower);
        if (m1.find()) {
            return Double.parseDouble(m1.group(1));
        }

        // 匹配 "try again in <seconds>"
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("try again in\\s*(\\d+(?:\\.\\d+)?)").matcher(lower);
        if (m2.find()) {
            return Double.parseDouble(m2.group(1));
        }

        return null;
    }

    /**
     * 一个统一 retry wrapper。
     *
     * 对应 Python 那种 provider._run_with_retry(...) 的核心思想。
     */
    public LLMResponse runWithRetry(Callable<LLMResponse> call) throws Exception {
        LLMResponse last = null;

        // 最多重试 CHAT_RETRY_DELAYS.size() + 1 次
        for (int i = 0; i <= CHAT_RETRY_DELAYS.size(); i++) {
            // 执行调用
            last = call.call();
            if (last == null) {
                return null;
            }

            // 如果不是错误状态或不是瞬态错误，直接返回
            if (!"error".equals(last.getFinishReason()) || !isTransientResponse(last)) {
                return last;
            }

            // 如果还有重试机会
            if (i < CHAT_RETRY_DELAYS.size()) {
                // 计算延迟时间：优先使用响应中的 Retry-After，否则使用预设延迟
                double delay = last.getRetryAfter() != null ? last.getRetryAfter() : CHAT_RETRY_DELAYS.get(i);
                try {
                    // 等待指定时间
                    Thread.sleep((long) (delay * 1000));
                } catch (InterruptedException e) {
                    // 如果线程被中断，恢复中断状态并返回当前结果
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
        }

        // 返回最后一次尝试的结果
        return last;
    }

    /**
     * 流式增量回调接口
     */
    @FunctionalInterface
    public interface StreamDeltaHandler {
        void handle(String delta) throws Exception;
    }

    /**
     * 流式结束回调接口
     */
    @FunctionalInterface
    public interface StreamEndHandler {
        void handle(LLMResponse response) throws Exception;
    }
}
