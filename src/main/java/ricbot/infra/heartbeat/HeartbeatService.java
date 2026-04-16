package ricbot.infra.heartbeat;

import ricbot.infra.common.HelperUtils;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * HeartbeatService：后台活跃度检测与自动任务触发。
 */
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private static final List<Map<String, Object>> HEARTBEAT_TOOL = buildHeartbeatTool();

    private final Path workspace;
    private final LLMProvider provider;
    private final String model;

    private final ExecuteHandler onExecute;

    private final NotifyHandler onNotify;

    private final int intervalSeconds;

    private final boolean enabled;

    private final String timezone;

    private ScheduledExecutorService scheduler;

    private ScheduledFuture<?> scheduledFuture;

    private volatile boolean running = false;
    private volatile Instant lastTickAt;
    private volatile String lastErrorMessage;

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
        this.scheduler = newScheduler();
    }

    @FunctionalInterface
    public interface ExecuteHandler {
        String execute(String tasks) throws Exception;
    }

    @FunctionalInterface
    public interface NotifyHandler {
        void notify(String response) throws Exception;
    }

    public Path getHeartbeatFile() {
        return workspace.resolve("HEARTBEAT.md");
    }

    public synchronized void start() {
        if (!enabled) {
            log.info("心跳服务已禁用");
            return;
        }
        if (running) {
            log.info("心跳服务已在运行");
            return;
        }
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = newScheduler();
        }

        running = true;
        int effectiveInterval = Math.max(1, intervalSeconds);
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                () -> {
                    if (!running) {
                        return;
                    }
                    try {
                        tick();
                        lastTickAt = Instant.now();
                        lastErrorMessage = null;
                    } catch (Exception e) {
                        lastErrorMessage = e.getMessage();
                        log.error("心跳服务错误：{}", e.getMessage(), e);
                    }
                },
                effectiveInterval,
                effectiveInterval,
                TimeUnit.SECONDS
        );

        log.info("心跳服务已启动（每 {} 秒）", effectiveInterval);
    }

    public synchronized void stop() {
        if (!running && (scheduler == null || scheduler.isShutdown())) {
            return;
        }
        running = false;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            scheduledFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public Instant getLastTickAt() {
        return lastTickAt;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

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

    private void tick() throws Exception {
        String content = readHeartbeatFile();
        if (content == null || content.isBlank()) {
            log.debug("心跳：HEARTBEAT.md 不存在或为空");
            return;
        }

        log.debug("心跳：正在检查任务…");

        try {
            HeartbeatDecision decision = decide(content);

            if (!"run".equals(decision.action())) {
                log.debug("心跳：OK（无需通知）");
                return;
            }

            log.info("心跳：发现任务，开始执行…");

            if (onExecute != null) {
                String response = onExecute.execute(decision.tasks());

                if (response != null && !response.isBlank()) {
                    boolean shouldNotify = EvaluateResponseHelper.evaluateResponse(
                            response,
                            decision.tasks(),
                            provider,
                            model
                    );

                    if (shouldNotify && onNotify != null) {
                        log.info("心跳：已完成，正在投递结果");
                        onNotify.notify(response);
                    } else {
                        log.info("心跳：后置评估抑制了通知");
                    }
                }
            }
        } catch (Exception e) {
            log.error("心跳：执行失败：{}", e.getMessage(), e);
            throw e;
        }
    }

    private HeartbeatDecision decide(String content) throws Exception {
        String currentTime = HelperUtils.currentTimeStr(timezone);

        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", "你是一个心跳代理。请调用 heartbeat 工具来汇报你的决策。");
        messages.add(system);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content",
                "当前时间：" + currentTime + "\n\n"
                        + "请阅读下面的 HEARTBEAT.md，并判断是否存在进行中的任务。\n\n"
                        + content
        );
        messages.add(user);

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

    public record HeartbeatDecision(String action, String tasks) {
    }

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

    private static List<Map<String, Object>> buildHeartbeatTool() {
        Map<String, Object> actionProp = new LinkedHashMap<>();
        actionProp.put("type", "string");
        actionProp.put("enum", List.of("skip", "run"));
        actionProp.put("description", "skip 表示无需执行，run 表示存在进行中的任务");

        Map<String, Object> tasksProp = new LinkedHashMap<>();
        tasksProp.put("type", "string");
        tasksProp.put("description", "对进行中任务的自然语言摘要（action=run 时必填）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", actionProp);
        properties.put("tasks", tasksProp);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("action"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "heartbeat");
        function.put("description", "在审阅任务后汇报心跳决策。");
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);

        return List.of(tool);
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-service");
            t.setDaemon(true);
            return t;
        });
    }
}
