package ricbot.domain.agent;

import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.skill.SkillsLoader;
import ricbot.domain.worker.WorkerRuntime;
import ricbot.domain.worker.WorkerState;
import ricbot.domain.worker.WorkerStore;
import ricbot.infra.config.Config;
import ricbot.infra.template.PromptTemplates;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.tool.api.BuiltinToolRegistrar;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.skill.ReadSkillTool;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs background Agent runs for Spawn workers.
 *
 * <p>WorkerRuntime owns identity, lifecycle and mailbox. This service owns the
 * model/tool Run and uses Futures only for local cancellation, never as the
 * completion notification or source of truth.</p>
 */
public final class SpawnWorkerService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SpawnWorkerService.class);
    private static final int MAX_ITERATIONS = 15;
    private static final int LABEL_MAX_LENGTH = 30;
    private static final String MAX_ITERATIONS_MESSAGE = "任务已结束，但未生成最终回复。";
    private static final String ERROR_MESSAGE = "错误：Worker 执行失败。";

    private final Path workspace;
    private final String model;
    private final int maxToolResultChars;
    private final Config.ExecToolConfig execConfig;
    private final Config.WebToolsConfig webConfig;
    private final boolean restrictToWorkspace;
    private final SkillsLoader skillsLoader;
    private final GraphRunService runner;
    private final WorkerRuntime workers;
    private final RunCheckpointStore checkpoints;
    private final RunEventSink runEvents;
    private final SideEffectStore sideEffects;
    private final ThreadPoolExecutor executor;
    private final Map<String, Future<?>> scheduled = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public SpawnWorkerService(
            LLMProvider provider,
            Path workspace,
            int maxToolResultChars,
            String model,
            Config.WebToolsConfig webConfig,
            Config.ExecToolConfig execConfig,
            boolean restrictToWorkspace,
            List<String> disabledSkills
    ) {
        if (provider == null) throw new IllegalArgumentException("provider is required");
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        this.workspace = workspace.toAbsolutePath().normalize();
        this.model = model != null && !model.isBlank() ? model : provider.getDefaultModel();
        this.maxToolResultChars = maxToolResultChars;
        this.webConfig = webConfig != null ? webConfig : new Config.WebToolsConfig();
        this.execConfig = execConfig != null ? execConfig : new Config.ExecToolConfig();
        this.restrictToWorkspace = restrictToWorkspace;
        this.skillsLoader = new SkillsLoader(this.workspace, null,
                new HashSet<>(disabledSkills != null ? disabledSkills : List.of()));
        this.runner = new GraphRunService(provider);
        this.workers = new WorkerRuntime(this.workspace);
        this.checkpoints = new FileRunCheckpointStore(this.workspace);
        this.runEvents = new FileRunJournalStore(this.workspace);
        this.sideEffects = new FileSideEffectStore(this.workspace);
        this.executor = new ThreadPoolExecutor(
                1,
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                30,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                new WorkerThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
        recoverUnfinishedWorkers();
    }

    public SpawnReceipt spawn(
            String task,
            String label,
            String originChannel,
            String originChatId,
            String sessionKey,
            String idempotencyKey
    ) {
        String goal = required(task, "task");
        String scope = !clean(sessionKey).isBlank()
                ? clean(sessionKey)
                : clean(originChannel) + ":" + clean(originChatId);
        if (scope.equals(":")) scope = "direct";
        String key = !clean(idempotencyKey).isBlank() ? clean(idempotencyKey) : UUID.randomUUID().toString();
        String displayLabel = !clean(label).isBlank() ? clean(label) : truncate(goal, LABEL_MAX_LENGTH);

        WorkerStore.StoredWorker controller = workers.create(
                scope,
                "CONTROLLER",
                "Collect Spawn worker results",
                "",
                "spawn-controller",
                Map.of("kind", "spawn-controller")
        );
        Optional<WorkerStore.StoredWorker> existing = workers.findByIdempotencyKey(scope, "spawn:" + key);
        WorkerStore.StoredWorker worker = workers.create(
                scope,
                "SPAWN",
                goal,
                controller.spec().workerId(),
                "spawn:" + key,
                Map.of(
                        "kind", "spawn",
                        "label", displayLabel,
                        "origin_channel", clean(originChannel),
                        "origin_chat_id", clean(originChatId),
                        "controller_worker_id", controller.spec().workerId()
                )
        );
        boolean created = existing.isEmpty();
        if (!settled(worker.state().status())) schedule(worker.spec().workerId());
        return new SpawnReceipt(
                worker.spec().workerId(),
                controller.spec().workerId(),
                worker.state().status(),
                created,
                key,
                displayLabel
        );
    }

    public Optional<WorkerStore.StoredWorker> worker(String workerId) {
        return workers.worker(workerId);
    }

    public List<WorkerStore.StoredWorker> workers(String sessionKey) {
        return workers.workers(sessionKey);
    }

    public List<WorkerStore.MailboxMessage> inbox(String workerId, boolean includeAcknowledged) {
        return workers.inbox(workerId, 0, includeAcknowledged);
    }

    public int cancelBySession(String sessionKey) {
        int cancelled = 0;
        for (WorkerStore.StoredWorker worker : workers.workers(required(sessionKey, "sessionKey"))) {
            if (!"SPAWN".equals(worker.spec().role()) || settled(worker.state().status())) continue;
            try {
                workers.cancel(worker.spec().workerId(), "session cancelled");
                sendCompletionOnce(requireWorker(worker.spec().workerId()),
                        new Announcement("任务已取消。", "CANCELLED", "cancelled"));
                cancelled++;
            } catch (IllegalStateException race) {
                if (!settled(requireWorker(worker.spec().workerId()).state().status())) throw race;
            }
            Future<?> future = scheduled.get(worker.spec().workerId());
            if (future != null) future.cancel(true);
        }
        return cancelled;
    }

    public int getRunningCount() {
        return (int) scheduled.values().stream().filter(future -> !future.isDone()).count();
    }

    private void recoverUnfinishedWorkers() {
        for (WorkerStore.StoredWorker worker : workers.workers()) {
            if (!"SPAWN".equals(worker.spec().role()) || settled(worker.state().status())) continue;
            Optional<WorkerStore.MailboxMessage> completion = completion(worker);
            if (completion.isPresent()) {
                settleFromCompletion(worker, completion.orElseThrow());
                continue;
            }
            WorkerState.Status status = worker.state().status();
            if (status == WorkerState.Status.RUNNING || status == WorkerState.Status.WAITING) {
                workers.recover(worker.spec().workerId(), "process restart");
                status = WorkerState.Status.RECOVERING;
            }
            if (status == WorkerState.Status.RECOVERING) {
                RunCheckpoint checkpoint = checkpoints.load(runSessionKey(worker.spec().workerId())).orElse(null);
                if (checkpoint != null && !checkpoint.pendingToolCalls().isEmpty()) {
                    complete(worker.spec().workerId(), new Announcement(
                            "Worker recovery stopped at an uncertain pending tool boundary.", "FAILED", "reconcile"));
                    continue;
                }
                workers.transition(worker.spec().workerId(), WorkerState.Status.READY, null, "safe recovery prepared");
            }
            schedule(worker.spec().workerId());
        }
    }

    private void schedule(String workerId) {
        while (true) {
            Future<?> current = scheduled.get(workerId);
            if (current != null && !current.isDone()) return;
            FutureTask<Void> task = new FutureTask<>(() -> {
                runWorker(workerId);
                return null;
            }) {
                @Override
                protected void done() {
                    scheduled.remove(workerId, this);
                }
            };
            boolean installed = current == null
                    ? scheduled.putIfAbsent(workerId, task) == null
                    : scheduled.replace(workerId, current, task);
            if (installed) {
                executor.execute(task);
                return;
            }
        }
    }

    private void runWorker(String workerId) {
        WorkerStore.StoredWorker worker = requireWorker(workerId);
        try {
            if (worker.state().status() == WorkerState.Status.CREATED) {
                workers.prepare(workerId);
                worker = requireWorker(workerId);
            }
            if (worker.state().status() == WorkerState.Status.READY) {
                workers.start(workerId, "run-" + UUID.randomUUID());
                worker = requireWorker(workerId);
            }
            if (worker.state().status() != WorkerState.Status.RUNNING) return;

            AgentRunResult result = runner.run(buildRunSpec(worker));
            if (requireWorker(workerId).state().status() == WorkerState.Status.CANCELLED) {
                complete(workerId, new Announcement("任务已取消。", "CANCELLED", "cancelled"));
            } else {
                complete(workerId, announcement(result));
            }
        } catch (CancellationException e) {
            interrupted(workerId, "cancelled");
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                interrupted(workerId, "interrupted");
            } else {
                log.warn("Spawn worker failed: workerId={}", workerId, e);
                complete(workerId, new Announcement(
                        "错误：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                        "FAILED",
                        "error"
                ));
            }
        }
    }

    private AgentRunSpec buildRunSpec(WorkerStore.StoredWorker worker) {
        String workerId = worker.spec().workerId();
        String sessionKey = runSessionKey(workerId);
        List<Map<String, Object>> messages = recoveryMessages(worker, sessionKey);
        return new AgentRunSpec()
                .setInitialMessages(messages)
                .setTools(buildWorkerTools())
                .setModel(model)
                .setMaxIterations(MAX_ITERATIONS)
                .setMaxToolResultChars(maxToolResultChars)
                .setHook(new WorkerRunHook(workerId))
                .setMaxIterationsMessage(MAX_ITERATIONS_MESSAGE)
                .setErrorMessage(null)
                .setFailOnToolError(true)
                .setWorkspace(workspace)
                .setSessionKey(sessionKey)
                .setCheckpointCallback(payload -> checkpoints.save(RunCheckpoint.fromPayload(
                        sessionKey, payload, Map.of("worker_id", workerId))))
                .setRunEventSink(runEvents)
                .setSideEffectStore(sideEffects)
                .setMetadata(Map.of("worker_id", workerId, "worker_role", "SPAWN"));
    }

    private List<Map<String, Object>> recoveryMessages(WorkerStore.StoredWorker worker, String sessionKey) {
        Optional<RunCheckpoint> checkpoint = checkpoints.load(sessionKey);
        if (checkpoint.isPresent() && checkpoint.orElseThrow().pendingToolCalls().isEmpty()
                && !checkpoint.orElseThrow().runMessages().isEmpty()) {
            return new ArrayList<>(checkpoint.orElseThrow().runMessages());
        }
        return new ArrayList<>(List.of(
                Map.of("role", "system", "content", buildWorkerPrompt(worker)),
                Map.of("role", "user", "content", worker.spec().goal())
        ));
    }

    private ToolRegistry buildWorkerTools() {
        ToolRegistry tools = new ToolRegistry();
        Path allowedDir = BuiltinToolRegistrar.allowedDir(workspace, restrictToWorkspace, execConfig);
        tools.register(new ReadSkillTool(skillsLoader));
        BuiltinToolRegistrar.registerFileAndSearchTools(tools, workspace, allowedDir);
        if (execConfig.isEnable()) {
            BuiltinToolRegistrar.registerExecTool(tools, workspace, restrictToWorkspace, execConfig);
        }
        if (webConfig.isEnable()) {
            tools.register(new WebFetchTool(webConfig.getMaxChars(), webConfig.getProxy()));
            tools.register(new WebSearchTool(webConfig.getSearch(), webConfig.getProxy()));
        }
        return tools;
    }

    private void interrupted(String workerId, String detail) {
        WorkerStore.StoredWorker worker = requireWorker(workerId);
        if (closing) {
            if (worker.state().status() == WorkerState.Status.RUNNING
                    || worker.state().status() == WorkerState.Status.WAITING) {
                workers.recover(workerId, "runtime shutdown");
            }
            return;
        }
        if (worker.state().status() != WorkerState.Status.CANCELLED) {
            workers.cancel(workerId, detail);
        }
        complete(workerId, new Announcement("任务已取消。", "CANCELLED", "cancelled"));
    }

    private void complete(String workerId, Announcement announcement) {
        WorkerStore.StoredWorker worker = requireWorker(workerId);
        sendCompletionOnce(worker, announcement);
        worker = requireWorker(workerId);
        if (settled(worker.state().status())) return;
        switch (announcement.status()) {
            case "COMPLETED" -> workers.complete(workerId, announcement.result());
            case "CANCELLED" -> workers.cancel(workerId, announcement.result());
            default -> workers.fail(workerId, announcement.result());
        }
    }

    private WorkerStore.MailboxMessage sendCompletionOnce(
            WorkerStore.StoredWorker worker, Announcement announcement) {
        Optional<WorkerStore.MailboxMessage> existing = completion(worker);
        if (existing.isPresent()) return existing.orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("completion_key", worker.spec().workerId());
        payload.put("worker_id", worker.spec().workerId());
        payload.put("status", announcement.status());
        payload.put("stop_reason", announcement.stopReason());
        payload.put("label", worker.spec().metadata().getOrDefault("label", ""));
        payload.put("task", worker.spec().goal());
        payload.put("result", announcement.result());
        return workers.send(
                worker.spec().workerId(),
                worker.spec().parentWorkerId(),
                WorkerStore.MessageKind.RESULT,
                worker.state().currentRunId(),
                payload
        );
    }

    private Optional<WorkerStore.MailboxMessage> completion(WorkerStore.StoredWorker worker) {
        if (worker.spec().parentWorkerId().isBlank()) return Optional.empty();
        return workers.inbox(worker.spec().parentWorkerId(), 0, true).stream()
                .filter(message -> worker.spec().workerId().equals(
                        String.valueOf(message.payload().getOrDefault("completion_key", ""))))
                .findFirst();
    }

    private void settleFromCompletion(WorkerStore.StoredWorker worker, WorkerStore.MailboxMessage message) {
        String status = String.valueOf(message.payload().getOrDefault("status", "FAILED"));
        String result = String.valueOf(message.payload().getOrDefault("result", ""));
        WorkerState.Status current = worker.state().status();
        if (current == WorkerState.Status.CREATED) {
            workers.prepare(worker.spec().workerId());
            workers.start(worker.spec().workerId(), message.correlationId());
        } else if (current == WorkerState.Status.READY) {
            workers.start(worker.spec().workerId(), message.correlationId());
        } else if (current == WorkerState.Status.RECOVERING) {
            workers.transition(worker.spec().workerId(), WorkerState.Status.RUNNING,
                    message.correlationId(), "completion recovered");
        }
        if ("COMPLETED".equals(status)) workers.complete(worker.spec().workerId(), result);
        else if ("CANCELLED".equals(status)) workers.cancel(worker.spec().workerId(), result);
        else workers.fail(worker.spec().workerId(), result);
    }

    private Announcement announcement(AgentRunResult result) {
        if (result == null) return new Announcement(ERROR_MESSAGE, "FAILED", "error");
        if ("tool_error".equals(result.getStopReason()) || "error".equals(result.getStopReason())) {
            String error = result.getError() != null ? result.getError() : ERROR_MESSAGE;
            return new Announcement(error, "FAILED", result.getStopReason());
        }
        if ("cancelled".equals(result.getStopReason())) {
            return new Announcement("任务已取消。", "CANCELLED", "cancelled");
        }
        return new Announcement(
                result.getFinalContent() != null ? result.getFinalContent() : MAX_ITERATIONS_MESSAGE,
                "COMPLETED",
                clean(result.getStopReason())
        );
    }

    private String buildWorkerPrompt(WorkerStore.StoredWorker worker) {
        String channel = String.valueOf(worker.spec().metadata().getOrDefault("origin_channel", ""));
        String chatId = String.valueOf(worker.spec().metadata().getOrDefault("origin_chat_id", ""));
        return PromptTemplates.renderTemplate(
                "agent/worker_system.md",
                true,
                Map.of(
                        "time_ctx", ContextBuilder.buildRuntimeContext(channel, chatId, null),
                        "workspace", String.valueOf(workspace),
                        "skills_summary", skillsLoader.buildSkillsSummary()
                )
        );
    }

    private WorkerStore.StoredWorker requireWorker(String workerId) {
        return workers.worker(workerId).orElseThrow(() ->
                new IllegalArgumentException("worker does not exist: " + workerId));
    }

    private static boolean settled(WorkerState.Status status) {
        return status == WorkerState.Status.COMPLETED
                || status == WorkerState.Status.FAILED
                || status == WorkerState.Status.CANCELLED;
    }

    private static String runSessionKey(String workerId) {
        return "worker:" + workerId;
    }

    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String truncate(String value, int maxLength) {
        String clean = clean(value);
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength) + "...";
    }

    @Override
    public void close() {
        closing = true;
        scheduled.values().forEach(future -> future.cancel(true));
        executor.shutdownNow();
        runner.close();
    }

    public record SpawnReceipt(
            String workerId,
            String controllerWorkerId,
            WorkerState.Status status,
            boolean created,
            String idempotencyKey,
            String label
    ) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "worker_id", workerId,
                    "mailbox_worker_id", controllerWorkerId,
                    "status", status.name(),
                    "created", created,
                    "idempotency_key", idempotencyKey,
                    "label", label
            );
        }
    }

    private record Announcement(String result, String status, String stopReason) {
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "worker-run-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class WorkerRunHook extends AgentHook {
        private final Logger workerLog;

        private WorkerRunHook(String workerId) {
            workerLog = LoggerFactory.getLogger("worker." + workerId);
        }

        @Override
        public void beforeExecuteTools(AgentHookContext context) {
            if (context.getToolCalls() == null) return;
            context.getToolCalls().forEach(call ->
                    workerLog.debug("tool: name={}, args={}", call.getName(), call.getArguments()));
        }
    }
}
