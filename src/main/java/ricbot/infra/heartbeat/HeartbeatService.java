package ricbot.infra.heartbeat;

// 导入用于评估响应是否应该通知的辅助类
// 导入用于获取当前时间字符串的辅助工具类
import ricbot.infra.common.HelperUtils;
// 导入 LLM 提供者接口，用于与大语言模型交互
import ricbot.integration.llm.api.LLMProvider;
// 导入 LLM 响应对象，包含模型返回的结果
import ricbot.integration.llm.api.LLMResponse;
// 导入工具调用请求对象，用于构建或解析工具调用
import ricbot.integration.llm.api.ToolCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 导入 IO 异常类
import java.io.IOException;
// 导入文件操作类
import java.nio.file.Files;
// 导入路径类，用于处理文件路径
import java.nio.file.Path;
// 导入即时时间点类，用于记录最后心跳时间
import java.time.Instant;
// 导入常用集合类
import java.util.*;
// 导入并发工具类，包括执行器服务和未来任务
import java.util.concurrent.*;

/**
 * HeartbeatService：后台活跃度检测与自动任务触发。
 *
 * 对应 Python heartbeat.py
 */
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    /**
     * 对应 Python 里的 _HEARTBEAT_TOOL
     *
     * Java 里直接构造成一个可序列化的 Map 结构，传给 provider。
     * 这是一个静态常量，在类加载时初始化，定义了心跳工具的 schema。
     */
    private static final List<Map<String, Object>> HEARTBEAT_TOOL = buildHeartbeatTool();

    // 工作空间路径，用于定位 HEARTBEAT.md 文件
    private final Path workspace;
    // LLM 提供者实例，用于与大模型通信
    private final LLMProvider provider;
    // 使用的模型名称
    private final String model;

    /**
     * Phase 2 执行回调：
     * 输入任务摘要，输出执行结果文本
     */
    private final ExecuteHandler onExecute;

    /**
     * 通知回调：
     * 把执行结果发给外部渠道
     */
    private final NotifyHandler onNotify;

    /**
     * 心跳间隔（秒）
     */
    private final int intervalSeconds;

    /**
     * 是否启用
     */
    private final boolean enabled;

    /**
     * 时区
     */
    private final String timezone;

    /**
     * 调度器
     * 用于定期执行心跳任务
     */
    private ScheduledExecutorService scheduler;

    /**
     * 当前 heartbeat 定时任务
     * 用于取消或管理当前的调度任务
     */
    private ScheduledFuture<?> scheduledFuture;

    /**
     * 是否运行中
     * 使用 volatile 保证多线程可见性
     */
    private volatile boolean running = false;
    // 最后一次成功心跳的时间点
    private volatile Instant lastTickAt;
    // 最后一次错误的消息内容
    private volatile String lastErrorMessage;

    /**
     * 构造函数
     *
     * @param workspace       工作空间路径
     * @param provider        LLM 提供者
     * @param model           模型名称
     * @param onExecute       执行回调
     * @param onNotify        通知回调
     * @param intervalSeconds 心跳间隔秒数
     * @param enabled         是否启用
     * @param timezone        时区
     */
    public HeartbeatService(
            Path workspace,
            LLMProvider provider,
            String model,
            ExecuteHandler onExecute,
            NotifyHandler onNotify,
            int intervalSeconds,
            boolean enabled,
            String timezone
    ) {
        this.workspace = workspace;
        this.provider = provider;
        this.model = model;
        this.onExecute = onExecute;
        this.onNotify = onNotify;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
        this.timezone = timezone;
        // 初始化调度器
        this.scheduler = newScheduler();
    }

    // =========================================================
    // Callback interfaces
    // =========================================================

    /**
     * 执行处理器接口
     * 用于执行具体的任务逻辑
     */
    @FunctionalInterface
    public interface ExecuteHandler {
        /**
         * 执行任务
         *
         * @param tasks 任务摘要
         * @return 执行结果文本
         * @throws Exception 执行异常
         */
        String execute(String tasks) throws Exception;
    }

    /**
     * 通知处理器接口
     * 用于发送通知
     */
    @FunctionalInterface
    public interface NotifyHandler {
        /**
         * 发送通知
         *
         * @param response 响应内容
         * @throws Exception 通知异常
         */
        void notify(String response) throws Exception;
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * 对应 Python: heartbeat_file property
     *
     * 获取心跳文件的路径
     *
     * @return HEARTBEAT.md 文件的路径
     */
    public Path getHeartbeatFile() {
        return workspace.resolve("HEARTBEAT.md");
    }

    /**
     * 对应 Python: start()
     *
     * 启动心跳服务
     * 如果服务已禁用或已在运行，则不执行任何操作
     * 否则，创建一个新的调度任务，定期执行 tick() 方法
     */
    public synchronized void start() {
        // 检查服务是否被禁用
        if (!enabled) {
            log.info("心跳服务已禁用");
            return;
        }
        // 检查服务是否已在运行
        if (running) {
            log.info("心跳服务已在运行");
            return;
        }
        // 如果调度器为空或已关闭，则重新创建
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = newScheduler();
        }

        // 标记服务为运行状态
        running = true;
        // 确保间隔至少为 1 秒
        int effectiveInterval = Math.max(1, intervalSeconds);
        // 调度固定延迟的任务
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                () -> {
                    // 如果服务不再运行，则直接返回
                    if (!running) {
                        return;
                    }
                    try {
                        // 执行心跳逻辑
                        tick();
                        // 更新最后心跳时间
                        lastTickAt = Instant.now();
                        // 清除错误信息
                        lastErrorMessage = null;
                    } catch (Exception e) {
                        // 记录错误信息
                        lastErrorMessage = e.getMessage();
                        log.error("心跳服务错误：{}", e.getMessage(), e);
                    }
                },
                effectiveInterval, // 初始延迟
                effectiveInterval, // 后续延迟
                TimeUnit.SECONDS   // 时间单位
        );

        log.info("心跳服务已启动（每 {} 秒）", effectiveInterval);
    }

    /**
     * 对应 Python: stop()
     *
     * 停止心跳服务
     * 取消当前的调度任务，并关闭调度器
     */
    public synchronized void stop() {
        // 如果服务未运行且调度器已关闭，则直接返回
        if (!running && (scheduler == null || scheduler.isShutdown())) {
            return;
        }
        // 标记服务为停止状态
        running = false;
        // 取消当前的调度任务
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            scheduledFuture = null;
        }
        // 关闭调度器
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 检查服务是否正在运行
     *
     * @return 如果正在运行返回 true，否则返回 false
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取最后一次心跳的时间
     *
     * @return 最后一次心跳的 Instant 对象
     */
    public Instant getLastTickAt() {
        return lastTickAt;
    }

    /**
     * 获取最后一次错误的消息
     *
     * @return 错误消息字符串
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    /**
     * 对应 Python: trigger_now()
     *
     * 手动触发一次 heartbeat
     * 读取心跳文件，决策是否执行任务，如果执行则返回结果
     *
     * @return 执行结果，如果无需执行则返回 null
     * @throws Exception 执行过程中的异常
     */
    public String triggerNow() throws Exception {
        // 读取心跳文件内容
        String content = readHeartbeatFile();
        // 如果内容为空或不存在，则返回 null
        if (content == null || content.isBlank()) {
            return null;
        }

        // 决策是否需要执行任务
        HeartbeatDecision decision = decide(content);
        // 如果决策不是 run 或者没有执行回调，则返回 null
        if (!"run".equals(decision.action()) || onExecute == null) {
            return null;
        }

        // 执行任务并返回结果
        return onExecute.execute(decision.tasks());
    }

    // =========================================================
    // Main heartbeat logic
    // =========================================================

    /**
     * 对应 Python: _tick()
     *
     * 心跳核心逻辑
     * 读取心跳文件，决策是否执行任务，如果执行则评估结果并决定是否通知
     *
     * @throws Exception 执行过程中的异常
     */
    private void tick() throws Exception {
        // 读取心跳文件内容
        String content = readHeartbeatFile();
        // 如果内容为空或不存在，则打印日志并返回
        if (content == null || content.isBlank()) {
            log.debug("心跳：HEARTBEAT.md 不存在或为空");
            return;
        }

        log.debug("心跳：正在检查任务…");

        try {
            // 决策是否需要执行任务
            HeartbeatDecision decision = decide(content);

            // 如果决策不是 run，则打印日志并返回
            if (!"run".equals(decision.action())) {
                log.debug("心跳：OK（无需通知）");
                return;
            }

            log.info("心跳：发现任务，开始执行…");

            // 如果有执行回调，则执行任务
            if (onExecute != null) {
                String response = onExecute.execute(decision.tasks());

                // 如果响应不为空
                if (response != null && !response.isBlank()) {
                    // 评估响应是否应该通知
                    boolean shouldNotify = EvaluateResponseHelper.evaluateResponse(
                            response,
                            decision.tasks(),
                            provider,
                            model
                    );

                    // 如果应该通知且有通知回调，则发送通知
                    if (shouldNotify && onNotify != null) {
                        log.info("心跳：已完成，正在投递结果");
                        onNotify.notify(response);
                    } else {
                        // 否则打印日志表示通知被抑制
                        log.info("心跳：后置评估抑制了通知");
                    }
                }
            }
        } catch (Exception e) {
            // 打印执行失败的错误信息
            log.error("心跳：执行失败：{}", e.getMessage(), e);
            // 重新抛出异常
            throw e;
        }
    }

    /**
     * 对应 Python: _decide(content)
     *
     * Phase 1:
     * 读取 HEARTBEAT.md 后，让 LLM 返回 tool call:
     * - action = skip / run
     * - tasks = 任务摘要
     *
     * @param content 心跳文件内容
     * @return 心跳决策结果
     * @throws Exception 决策过程中的异常
     */
    private HeartbeatDecision decide(String content) throws Exception {
        // 获取当前时间字符串
        String currentTime = HelperUtils.currentTimeStr(timezone);

        // 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();

        // 构建系统消息
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", "你是一个心跳代理。请调用 heartbeat 工具来汇报你的决策。");
        messages.add(system);

        // 构建用户消息
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content",
                "当前时间：" + currentTime + "\n\n"
                        + "请阅读下面的 HEARTBEAT.md，并判断是否存在进行中的任务。\n\n"
                        + content
        );
        messages.add(user);

        // 这里假设你前面已经有 provider.chatWithRetry(...) 风格的方法；
        // 如果你的 Java Provider 还没完全对齐，可以把这里再适配一下。
        // 调用 LLM 提供商的聊天方法，传入消息、工具和模型
        Object rawResponse = provider.chatWithRetry(
                messages,
                HEARTBEAT_TOOL,
                model
        );

        // 将原始响应转换为 ProviderResponse 对象
        ProviderResponse response = ProviderResponse.from(rawResponse);

        // 如果没有工具调用，则返回 skip 决策
        if (!response.hasToolCalls()) {
            return new HeartbeatDecision("skip", "");
        }

        // 获取第一个工具调用的参数
        Map<String, Object> args = response.firstToolArguments();
        // 提取 action 参数，默认为 skip
        String action = args.get("action") != null ? String.valueOf(args.get("action")) : "skip";
        // 提取 tasks 参数，默认为空字符串
        String tasks = args.get("tasks") != null ? String.valueOf(args.get("tasks")) : "";

        // 返回决策结果
        return new HeartbeatDecision(action, tasks);
    }

    // =========================================================
    // File helpers
    // =========================================================

    /**
     * 对应 Python: _read_heartbeat_file()
     *
     * 读取心跳文件内容
     *
     * @return 文件内容字符串，如果文件不存在或读取失败则返回 null
     */
    private String readHeartbeatFile() {
        // 获取心跳文件路径
        Path heartbeatFile = getHeartbeatFile();
        // 如果文件不存在，则返回 null
        if (!Files.exists(heartbeatFile)) {
            return null;
        }

        try {
            // 读取文件内容并返回
            return Files.readString(heartbeatFile);
        } catch (IOException e) {
            // 如果读取失败，则返回 null
            return null;
        }
    }

    // =========================================================
    // Helper DTOs
    // =========================================================

    /**
     * Heartbeat 决策结果
     *
     * @param action 动作类型 (skip/run)
     * @param tasks  任务摘要
     */
    public record HeartbeatDecision(String action, String tasks) {
    }

    /**
     * 对 provider 返回值做一个统一适配层。
     *
     * 这是因为你前面转写的 Provider 结构可能还没完全定死，
     * 这里用一个宽松适配，避免你后面大改 HeartbeatService。
     */
    public static class ProviderResponse {
        // 是否有工具调用
        private boolean hasToolCalls;
        // 工具调用列表
        private List<Map<String, Object>> toolCalls = new ArrayList<>();

        /**
         * 检查是否有工具调用
         *
         * @return 如果有工具调用返回 true，否则返回 false
         */
        public boolean hasToolCalls() {
            return hasToolCalls;
        }

        /**
         * 设置是否有工具调用
         *
         * @param hasToolCalls 是否有工具调用
         */
        public void setHasToolCalls(boolean hasToolCalls) {
            this.hasToolCalls = hasToolCalls;
        }

        /**
         * 获取工具调用列表
         *
         * @return 工具调用列表
         */
        public List<Map<String, Object>> getToolCalls() {
            return toolCalls;
        }

        /**
         * 设置工具调用列表
         *
         * @param toolCalls 工具调用列表
         */
        public void setToolCalls(List<Map<String, Object>> toolCalls) {
            this.toolCalls = toolCalls;
        }

        /**
         * 获取第一个工具调用的参数
         *
         * @return 参数 Map，如果没有工具调用则返回空 Map
         */
        public Map<String, Object> firstToolArguments() {
            // 如果工具调用列表为空，则返回空 Map
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Collections.emptyMap();
            }

            // 获取第一个工具调用
            Map<String, Object> first = toolCalls.get(0);

            // 尝试从 arguments 字段获取参数
            Object args = first.get("arguments");
            if (args instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                return cast;
            }

            // 尝试从 function.arguments 字段获取参数
            Object function = first.get("function");
            if (function instanceof Map<?, ?> fnMap) {
                Object fnArgs = ((Map<?, ?>) fnMap).get("arguments");
                if (fnArgs instanceof Map<?, ?> argMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) argMap;
                    return cast;
                }
            }

            // 如果都找不到，则返回空 Map
            return Collections.emptyMap();
        }

        /**
         * 宽松适配 provider 原始响应
         *
         * @param raw 原始响应对象
         * @return 适配后的 ProviderResponse 对象
         */
        @SuppressWarnings("unchecked")
        public static ProviderResponse from(Object raw) {
            // 创建新的 ProviderResponse 对象
            ProviderResponse r = new ProviderResponse();

            // 如果原始响应为空，则返回空对象
            if (raw == null) {
                return r;
            }

            // 如果原始响应是 LLMResponse 类型
            if (raw instanceof LLMResponse lr) {
                // 设置是否有工具调用
                r.setHasToolCalls(lr.hasToolCalls());
                // 如果有工具调用
                if (lr.getToolCalls() != null) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    // 遍历工具调用列表
                    for (ToolCallRequest tc : lr.getToolCalls()) {
                        Map<String, Object> m = new HashMap<>();
                        // 提取 id, name, arguments
                        m.put("id", tc.getId());
                        m.put("name", tc.getName());
                        m.put("arguments", tc.getArguments());
                        calls.add(m);
                    }
                    // 设置工具调用列表
                    r.setToolCalls(calls);
                }
                return r;
            }

            // 如果你的 provider 已经有统一 Response 类，
            // 可以把这里替换成强类型转换。
            // 如果原始响应已经是 ProviderResponse 类型，则直接返回
            if (raw instanceof ProviderResponse pr) {
                return pr;
            }

            // 如果原始响应是 Map 类型
            if (raw instanceof Map<?, ?> map) {
                // 提取 has_tool_calls 字段
                Object hasToolCalls = map.get("has_tool_calls");
                if (hasToolCalls instanceof Boolean b) {
                    r.setHasToolCalls(b);
                }

                // 提取 tool_calls 字段
                Object toolCalls = map.get("tool_calls");
                if (toolCalls instanceof List<?> list) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    // 遍历工具调用列表
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            result.add((Map<String, Object>) m);
                        }
                    }
                    // 设置工具调用列表
                    r.setToolCalls(result);
                }

                return r;
            }

            // 尝试通过反射获取 hasToolCalls 方法
            try {
                var hasToolCallsMethod = raw.getClass().getMethod("hasToolCalls");
                Object hasToolCalls = hasToolCallsMethod.invoke(raw);
                if (hasToolCalls instanceof Boolean b) {
                    r.setHasToolCalls(b);
                }
            } catch (Exception ignored) {
                // 忽略异常
            }

            // 尝试通过反射获取 getToolCalls 方法
            try {
                var toolCallsMethod = raw.getClass().getMethod("getToolCalls");
                Object toolCalls = toolCallsMethod.invoke(raw);
                if (toolCalls instanceof List<?> list) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    // 遍历工具调用列表
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            result.add((Map<String, Object>) m);
                        } else {
                            // 再做一层反射适配
                            Map<String, Object> converted = new LinkedHashMap<>();
                            try {
                                // 尝试获取 getArguments 方法
                                var argsMethod = item.getClass().getMethod("getArguments");
                                converted.put("arguments", argsMethod.invoke(item));
                            } catch (Exception ignored) {
                                // 忽略异常
                            }
                            try {
                                // 尝试获取 getFunction 方法
                                var fnMethod = item.getClass().getMethod("getFunction");
                                converted.put("function", fnMethod.invoke(item));
                            } catch (Exception ignored) {
                                // 忽略异常
                            }
                            result.add(converted);
                        }
                    }
                    // 设置工具调用列表
                    r.setToolCalls(result);
                }
            } catch (Exception ignored) {
                // 忽略异常
            }

            // 返回适配后的对象
            return r;
        }
    }

    // =========================================================
    // Static helpers
    // =========================================================

    /**
     * 构建心跳工具的 schema
     *
     * @return 工具定义的列表
     */
    private static List<Map<String, Object>> buildHeartbeatTool() {
        // 定义 action 属性
        Map<String, Object> actionProp = new LinkedHashMap<>();
        actionProp.put("type", "string");
        actionProp.put("enum", List.of("skip", "run"));
        actionProp.put("description", "skip 表示无需执行，run 表示存在进行中的任务");

        // 定义 tasks 属性
        Map<String, Object> tasksProp = new LinkedHashMap<>();
        tasksProp.put("type", "string");
        tasksProp.put("description", "对进行中任务的自然语言摘要（action=run 时必填）");

        // 定义 properties
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", actionProp);
        properties.put("tasks", tasksProp);

        // 定义 parameters
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("action"));

        // 定义 function
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "heartbeat");
        function.put("description", "在审阅任务后汇报心跳决策。");
        function.put("parameters", parameters);

        // 定义 tool
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);

        // 返回包含单个工具的列表
        return List.of(tool);
    }

    /**
     * 创建新的调度器
     *
     * @return 单线程的ScheduledExecutorService
     */
    private static ScheduledExecutorService newScheduler() {
        // 创建一个单线程的ScheduledExecutorService
        return Executors.newSingleThreadScheduledExecutor(r -> {
            // 创建新线程
            Thread t = new Thread(r, "heartbeat-service");
            // 设置为守护线程
            t.setDaemon(true);
            return t;
        });
    }
}
