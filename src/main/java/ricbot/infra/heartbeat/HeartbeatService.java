package ricbot.infra.heartbeat;


import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.LLMResponse;
import ricbot.llm.api.ToolCallRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * HeartbeatService：后台活跃度检测与自动任务触发。
 *
 * 对应 Python heartbeat.py
 */
public class HeartbeatService {

    /**
     * 对应 Python 里的 _HEARTBEAT_TOOL
     *
     * Java 里直接构造成一个可序列化的 Map 结构，传给 provider。
     */
    private static final List<Map<String, Object>> HEARTBEAT_TOOL = buildHeartbeatTool();

    private final Path workspace;
    private final LLMProvider provider;
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
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * 当前 heartbeat 定时任务
     */
    private ScheduledFuture<?> scheduledFuture;

    /**
     * 是否运行中
     */
    private volatile boolean running = false;

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
    }

    // =========================================================
    // Callback interfaces
    // =========================================================

    @FunctionalInterface
    public interface ExecuteHandler {
        String execute(String tasks) throws Exception;
    }

    @FunctionalInterface
    public interface NotifyHandler {
        void notify(String response) throws Exception;
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * 对应 Python: heartbeat_file property
     */
    public Path getHeartbeatFile() {
        return workspace.resolve("HEARTBEAT.md");
    }

    /**
     * 对应 Python: start()
     */
    public synchronized void start() {
        if (!enabled) {
            System.out.println("Heartbeat disabled");
            return;
        }
        if (running) {
            System.out.println("Heartbeat already running");
            return;
        }

        running = true;
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                () -> {
                    if (!running) {
                        return;
                    }
                    try {
                        tick();
                    } catch (Exception e) {
                        System.err.println("Heartbeat error: " + e.getMessage());
                    }
                },
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        System.out.println("Heartbeat started (every " + intervalSeconds + "s)");
    }

    /**
     * 对应 Python: stop()
     */
    public synchronized void stop() {
        running = false;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            scheduledFuture = null;
        }
        scheduler.shutdownNow();
    }

    /**
     * 对应 Python: trigger_now()
     *
     * 手动触发一次 heartbeat
     */
    public String triggerNow() throws Exception {
        String content = readHeartbeatFile();
        if (content == null || content.isBlank()) {
            return null;
        }

        HeartbeatDecision decision = decide(content);
        if (!"run".equals(decision.action()) || onExecute == null) {
            return null;
        }

        return onExecute.execute(decision.tasks());
    }

    // =========================================================
    // Main heartbeat logic
    // =========================================================

    /**
     * 对应 Python: _tick()
     */
    private void tick() throws Exception {
        String content = readHeartbeatFile();
        if (content == null || content.isBlank()) {
            System.out.println("Heartbeat: HEARTBEAT.md missing or empty");
            return;
        }

        System.out.println("Heartbeat: checking for tasks...");

        try {
            HeartbeatDecision decision = decide(content);

            if (!"run".equals(decision.action())) {
                System.out.println("Heartbeat: OK (nothing to report)");
                return;
            }

            System.out.println("Heartbeat: tasks found, executing...");

            if (onExecute != null) {
                String response = onExecute.execute(decision.tasks());

                if (response != null && !response.isBlank()) {
                    boolean shouldNotify = evaluateResponse(response, decision.tasks());

                    if (shouldNotify && onNotify != null) {
                        System.out.println("Heartbeat: completed, delivering response");
                        onNotify.notify(response);
                    } else {
                        System.out.println("Heartbeat: silenced by post-run evaluation");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Heartbeat execution failed: " + e.getMessage());
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
     */
    private HeartbeatDecision decide(String content) throws Exception {
        String currentTime = currentTimeStr(timezone);

        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", "You are a heartbeat agent. Call the heartbeat tool to report your decision.");
        messages.add(system);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content",
                "Current Time: " + currentTime + "\n\n"
                        + "Review the following HEARTBEAT.md and decide whether there are active tasks.\n\n"
                        + content
        );
        messages.add(user);

        // 这里假设你前面已经有 provider.chatWithRetry(...) 风格的方法；
        // 如果你的 Java Provider 还没完全对齐，可以把这里再适配一下。
        Object rawResponse = provider.chatWithRetry(
                messages,
                HEARTBEAT_TOOL,
                model
        );

        ProviderResponse response = ProviderResponse.from(rawResponse);

        if (!response.hasToolCalls()) {
            return new HeartbeatDecision("skip", "");
        }

        Map<String, Object> args = response.firstToolArguments();
        String action = args.get("action") != null ? String.valueOf(args.get("action")) : "skip";
        String tasks = args.get("tasks") != null ? String.valueOf(args.get("tasks")) : "";

        return new HeartbeatDecision(action, tasks);
    }

    // =========================================================
    // File helpers
    // =========================================================

    /**
     * 对应 Python: _read_heartbeat_file()
     */
    private String readHeartbeatFile() {
        Path heartbeatFile = getHeartbeatFile();
        if (!Files.exists(heartbeatFile)) {
            return null;
        }

        try {
            return Files.readString(heartbeatFile);
        } catch (IOException e) {
            return null;
        }
    }

    // =========================================================
    // Post-run evaluator
    // =========================================================

    /**
     * 对应 Python:
     * from nanobot.utils.evaluator import evaluate_response
     *
     * 这里先给你一个兼容版占位实现。
     *
     * 后面你如果继续转 evaluator.py，
     * 就把这里替换成真正的 LLM 评估逻辑。
     */
    private boolean evaluateResponse(String response, String tasks) {
        if (response == null || response.isBlank()) {
            return false;
        }

        // 简化策略：
        // 1. 空响应不通知
        // 2. 明显无结果类文本不通知
        // 3. 其余默认通知
        String lower = response.toLowerCase(Locale.ROOT);

        if (lower.contains("nothing to do")
                || lower.contains("no active tasks")
                || lower.contains("no changes")
                || lower.contains("skip")) {
            return false;
        }

        return true;
    }

    // =========================================================
    // Helper DTOs
    // =========================================================

    /**
     * Heartbeat 决策结果
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
        private boolean hasToolCalls;
        private List<Map<String, Object>> toolCalls = new ArrayList<>();

        public boolean hasToolCalls() {
            return hasToolCalls;
        }

        public void setHasToolCalls(boolean hasToolCalls) {
            this.hasToolCalls = hasToolCalls;
        }

        public List<Map<String, Object>> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<Map<String, Object>> toolCalls) {
            this.toolCalls = toolCalls;
        }

        public Map<String, Object> firstToolArguments() {
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, Object> first = toolCalls.get(0);

            Object args = first.get("arguments");
            if (args instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                return cast;
            }

            Object function = first.get("function");
            if (function instanceof Map<?, ?> fnMap) {
                Object fnArgs = ((Map<?, ?>) fnMap).get("arguments");
                if (fnArgs instanceof Map<?, ?> argMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) argMap;
                    return cast;
                }
            }

            return Collections.emptyMap();
        }

        /**
         * 宽松适配 provider 原始响应
         */
        @SuppressWarnings("unchecked")
        public static ProviderResponse from(Object raw) {
            ProviderResponse r = new ProviderResponse();

            if (raw == null) {
                return r;
            }

            if (raw instanceof LLMResponse lr) {
                r.setHasToolCalls(lr.hasToolCalls());
                if (lr.getToolCalls() != null) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    for (ToolCallRequest tc : lr.getToolCalls()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", tc.getId());
                        m.put("name", tc.getName());
                        m.put("arguments", tc.getArguments());
                        calls.add(m);
                    }
                    r.setToolCalls(calls);
                }
                return r;
            }

            // 如果你的 provider 已经有统一 Response 类，
            // 可以把这里替换成强类型转换。
            if (raw instanceof ProviderResponse pr) {
                return pr;
            }

            if (raw instanceof Map<?, ?> map) {
                Object hasToolCalls = map.get("has_tool_calls");
                if (hasToolCalls instanceof Boolean b) {
                    r.setHasToolCalls(b);
                }

                Object toolCalls = map.get("tool_calls");
                if (toolCalls instanceof List<?> list) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            result.add((Map<String, Object>) m);
                        }
                    }
                    r.setToolCalls(result);
                }

                return r;
            }

            try {
                var hasToolCallsMethod = raw.getClass().getMethod("hasToolCalls");
                Object hasToolCalls = hasToolCallsMethod.invoke(raw);
                if (hasToolCalls instanceof Boolean b) {
                    r.setHasToolCalls(b);
                }
            } catch (Exception ignored) {
            }

            try {
                var toolCallsMethod = raw.getClass().getMethod("getToolCalls");
                Object toolCalls = toolCallsMethod.invoke(raw);
                if (toolCalls instanceof List<?> list) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            result.add((Map<String, Object>) m);
                        } else {
                            // 再做一层反射适配
                            Map<String, Object> converted = new LinkedHashMap<>();
                            try {
                                var argsMethod = item.getClass().getMethod("getArguments");
                                converted.put("arguments", argsMethod.invoke(item));
                            } catch (Exception ignored) {
                            }
                            try {
                                var fnMethod = item.getClass().getMethod("getFunction");
                                converted.put("function", fnMethod.invoke(item));
                            } catch (Exception ignored) {
                            }
                            result.add(converted);
                        }
                    }
                    r.setToolCalls(result);
                }
            } catch (Exception ignored) {
            }

            return r;
        }
    }

    // =========================================================
    // Static helpers
    // =========================================================

    private static List<Map<String, Object>> buildHeartbeatTool() {
        Map<String, Object> actionProp = new LinkedHashMap<>();
        actionProp.put("type", "string");
        actionProp.put("enum", List.of("skip", "run"));
        actionProp.put("description", "skip = nothing to do, run = has active tasks");

        Map<String, Object> tasksProp = new LinkedHashMap<>();
        tasksProp.put("type", "string");
        tasksProp.put("description", "Natural-language summary of active tasks (required for run)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", actionProp);
        properties.put("tasks", tasksProp);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("action"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "heartbeat");
        function.put("description", "Report heartbeat decision after reviewing tasks.");
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);

        return List.of(tool);
    }

    /**
     * 对应 Python:
     * from nanobot.utils.helpers import current_time_str
     *
     * 这里先内置一个简化版。
     */
    private static String currentTimeStr(String timezone) {
        ZoneId zone;
        try {
            zone = (timezone != null && !timezone.isBlank())
                    ? ZoneId.of(timezone)
                    : ZoneId.systemDefault();
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }

        return ZonedDateTime.now(zone).toString();
    }
}