package ricbot.domain.agent;

import ricbot.domain.memory.Consolidator;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record SessionPreparationService(SessionManager sessionManager, AutoCompact autoCompact, Consolidator consolidator) {

    @FunctionalInterface
    interface CommandDispatcher {
        OutboundMessage dispatch(InboundMessage msg, Session session, String sessionKey, String raw);
    }

    /**
     * 准备交互式对话轮次的上下文。
     *
     * @param msg               接收到的入站消息
     * @param sessionKey        会话的唯一标识键
     * @param commandDispatcher 命令分发器，用于处理以 "/" 开头的特殊指令
     * @return 包含处理后会话状态和可能立即响应的 PreparedSessionContext 对象
     */
    PreparedSessionContext prepareInteractiveTurn(InboundMessage msg, String sessionKey, CommandDispatcher commandDispatcher) {
        // 获取或创建基础会话对象
        Session baseSession = sessionManager.getOrCreate(sessionKey);

        // 尝试进行会话自动压缩准备（如生成摘要等）
        AutoCompact.PreparedSession prepared = autoCompact.prepareSession(baseSession, sessionKey);

        // 如果压缩准备成功，使用新的会话实例和摘要；否则使用原始会话且无摘要
        Session session = prepared != null ? prepared.session() : baseSession;
        String summaryContext = prepared != null ? prepared.summary() : null;

        // 根据 Token 数量判断是否需要合并/压缩历史消息
        consolidator.maybeConsolidateByTokens(session);

        // 恢复运行时检查点（如中断前的工具调用状态、任务状态等）
        restoreRuntimeCheckpoint(session);

        // 恢复待处理的用户轮次标记（处理之前未完成的交互）
        restorePendingUserTurn(session);

        // 初始化立即响应对象，默认为 null
        OutboundMessage immediateResponse = null;

        // 获取并修剪消息内容
        String raw = trim(msg.getContent());

        // 如果消息内容非空且以 "/" 开头，视为命令，通过分发器处理并获取立即响应
        if (raw.startsWith("/")) {
            immediateResponse = commandDispatcher.dispatch(msg, session, sessionKey, raw);
        }

        // 构建并返回准备好的会话上下文，包含会话信息、摘要、任务状态及可能的立即响应
        return new PreparedSessionContext(sessionKey, session, summaryContext, TaskState.fromSession(session), immediateResponse, false);
    }

    /**
     * 准备系统触发的对话轮次上下文（例如定时任务、后台处理等非用户直接交互场景）。
     *
     * @param sessionKey 会话的唯一标识键
     * @return 包含处理后会话状态和可能立即响应的 PreparedSessionContext 对象
     */
    PreparedSessionContext prepareSystemTurn(String sessionKey) {
        // 获取或创建基础会话对象
        Session session = sessionManager.getOrCreate(sessionKey);

        // 恢复运行时检查点（如中断前的工具调用状态、任务状态等）
        restoreRuntimeCheckpoint(session);

        // 恢复待处理的用户轮次标记（处理之前未完成的交互）
        restorePendingUserTurn(session);

        // 构建并返回准备好的会话上下文，不包含摘要和立即响应，标记为非交互式
        return new PreparedSessionContext(sessionKey, session, null, TaskState.fromSession(session), null, false);
    }

    /**
     * 如果需要，持久化用户轮次消息到会话中。
     *
     * @param prepared 准备好的会话上下文对象
     * @param msg      接收到的入站消息
     * @return 更新后的 PreparedSessionContext 对象，包含持久化后的状态
     */
    PreparedSessionContext persistUserTurnIfNeeded(PreparedSessionContext prepared, InboundMessage msg) {
        // 如果消息内容为空或仅包含空白字符，则直接返回原始上下文，不进行持久化操作
        if (msg.getContent() == null || msg.getContent().isBlank()) {
            return prepared;
        }

        // 将用户消息添加到会话的消息历史中，角色标记为 "user"
        prepared.session().addMessage("user", msg.getContent());
        // 在会话元数据中标记存在待处理的用户轮次，用于后续的状态恢复或中断处理
        prepared.session().getMetadata().put(SessionRuntimeKeys.PENDING_USER_TURN_KEY, true);

        // 从当前会话中加载任务状态对象
        TaskState taskState = TaskState.fromSession(prepared.session());
        // 记录新轮次的开始，传入用户消息内容作为上下文
        taskState.beginTurn(msg.getContent());
        // 将更新后的任务状态持久化回会话元数据中
        taskState.persist(prepared.session());
        // 保存整个会话对象到存储层，确保消息和元数据的变更被持久化
        sessionManager.save(prepared.session());

        // 返回一个新的 PreparedSessionContext 对象，其中包含任务状态的快照，并标记用户消息已提前持久化
        return prepared.withTaskStateSnapshot(taskState).withUserPersistedEarly();
    }

    void restoreRuntimeCheckpoint(Session session) {
        // 从会话元数据中获取运行时检查点对象
        Object raw = session.getMetadata().get(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        // 如果检查点不是 Map 类型，则直接返回，不进行恢复操作
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return;
        }

        // 将原始对象复制为 Map<String, Object> 以便后续处理
        Map<String, Object> checkpoint = copyObjectMap(rawMap);
        // 提取检查点中的各个组成部分：助手消息、已完成的工具结果、待处理的工具调用、任务状态
        Object assistantMessage = checkpoint.get("assistant_message");
        Object completedToolResults = checkpoint.get("completed_tool_results");
        Object pendingToolCalls = checkpoint.get("pending_tool_calls");
        Object taskState = checkpoint.get("task_state");

        // 如果存在助手消息且为 Map 类型，将其添加到会话消息列表中
        if (assistantMessage instanceof Map<?, ?> assistant) {
            session.getMessages().add(copyObjectMap(assistant));
        }

        // 如果存在已完成的工具结果列表，遍历并添加每个有效的结果消息到会话中
        if (completedToolResults instanceof List<?> completed) {
            for (Object item : completed) {
                if (item instanceof Map<?, ?> result) {
                    session.getMessages().add(copyObjectMap(result));
                }
            }
        }

        // 如果存在待处理的工具调用列表，为每个未完成的调用生成一个中断错误消息并添加到会话中
        if (pendingToolCalls instanceof List<?> pending) {
            for (Object item : pending) {
                // 跳过非 Map 类型的无效项
                if (!(item instanceof Map<?, ?> toolCall)) {
                    continue;
                }

                // 提取工具调用的函数定义，如果不存在则创建空 Map
                Map<String, Object> function = toolCall.get("function") instanceof Map<?, ?> fn
                        ? copyObjectMap(fn)
                        : new LinkedHashMap<>();

                // 构建表示工具执行中断的消息对象
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool"); // 角色标记为 tool
                toolMessage.put("tool_call_id", toolCall.get("id")); // 关联的工具调用 ID
                toolMessage.put("name", function.getOrDefault("name", "tool")); // 工具名称，默认为 "tool"
                toolMessage.put("content", interruptedToolMessage(checkpoint, session)); // 中断原因描述
                toolMessage.put("timestamp", Instant.now().toString()); // 当前时间戳
                session.getMessages().add(toolMessage); // 将中断消息加入会话历史
            }
        }

        // 如果存在任务状态且为 Map 类型，将其恢复至会话元数据中
        if (taskState instanceof Map<?, ?> taskMap) {
            session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, copyObjectMap(taskMap));
        }

        // 清理元数据中的临时标记和已恢复的检查点数据
        session.getMetadata().remove(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.RUNTIME_CHECKPOINT_KEY);
        // 保存更新后的会话状态
        sessionManager.save(session);
    }

    void restorePendingUserTurn(Session session) {
        // 从会话元数据中获取待处理用户轮次的标记
        Object flag = session.getMetadata().get(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        // 如果标记不存在、不是布尔类型或为 false，则直接返回，无需恢复
        if (!(flag instanceof Boolean b) || !b) {
            return;
        }

        // 获取会话中的消息列表
        List<Map<String, Object>> messages = session.getMessages();
        // 如果消息列表不为空，检查最后一条消息是否为用户消息
        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            // 如果最后一条消息的角色是 "user"，说明用户消息已持久化但助手未回复，需补充中断提示
            if ("user".equals(last.get("role"))) {
                // 创建一个新的助手消息对象，用于表示任务中断
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", "错误：任务在生成回复前被中断。");
                assistant.put("timestamp", Instant.now().toString());
                // 将中断提示消息添加到会话历史中
                messages.add(assistant);
                // 更新会话的最后修改时间为当前时间
                session.setUpdatedAt(Instant.now());
            }
        }

        // 清除元数据中的待处理用户轮次标记，避免重复处理
        session.getMetadata().remove(SessionRuntimeKeys.PENDING_USER_TURN_KEY);
        // 保存更新后的会话状态到存储层
        sessionManager.save(session);
    }

    private String interruptedToolMessage(Map<String, Object> checkpoint, Session session) {
        String reason = null;
        Object checkpointReason = checkpoint.get("interruption_reason");
        if (checkpointReason instanceof String s && !s.isBlank()) {
            reason = s;
        }
        if (reason == null) {
            Object sessionReason = session.getMetadata().get("_last_interrupt_reason");
            if (sessionReason instanceof String s && !s.isBlank()) {
                reason = s;
            }
        }
        if (reason == null) {
            reason = "interrupted";
        }

        return switch (reason) {
            case "manual_stop" -> "错误：任务在该工具执行完成前被手动停止。";
            case "shutdown" -> "错误：任务在该工具执行完成前因服务关闭而中断。";
            case "timeout" -> "错误：任务在该工具执行完成前因超时而中断。";
            default -> "错误：任务在该工具执行完成前被中断。";
        };
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
