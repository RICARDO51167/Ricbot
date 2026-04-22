package ricbot.domain.agent;

import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

// 准备会话上下文，用于封装会话处理过程中的关键状态和数据
final class PreparedSessionContext {

    // 会话的唯一标识键
    private final String sessionKey;
    // 会话对象，包含会话的核心信息
    private final Session session;
    // 归档的会话摘要信息
    private final String archivedSummary;
    // 任务状态的快照，用于记录当前任务的执行状态
    private final TaskState taskStateSnapshot;
    // 需要立即发送的出站消息
    private final OutboundMessage immediateResponse;
    // 标记用户是否已提前持久化会话状态
    private final boolean userPersistedEarly;

    // 构造函数，初始化所有最终字段
    PreparedSessionContext(
            String sessionKey,
            Session session,
            String archivedSummary,
            TaskState taskStateSnapshot,
            OutboundMessage immediateResponse,
            boolean userPersistedEarly
    ) {
        this.sessionKey = sessionKey;
        this.session = session;
        this.archivedSummary = archivedSummary;
        this.taskStateSnapshot = taskStateSnapshot;
        this.immediateResponse = immediateResponse;
        this.userPersistedEarly = userPersistedEarly;
    }

    // 获取会话键
    String sessionKey() {
        return sessionKey;
    }

    // 获取会话对象
    Session session() {
        return session;
    }

    // 获取归档摘要
    String archivedSummary() {
        return archivedSummary;
    }

    // 获取任务状态快照
    TaskState taskStateSnapshot() {
        return taskStateSnapshot;
    }

    // 获取立即响应消息
    OutboundMessage immediateResponse() {
        return immediateResponse;
    }

    // 获取用户是否提前持久化的标志
    boolean userPersistedEarly() {
        return userPersistedEarly;
    }

    // 创建一个新的上下文实例，仅更新用户提前持久化的标志
    PreparedSessionContext withUserPersistedEarly(boolean userPersistedEarly) {
        return new PreparedSessionContext(sessionKey, session, archivedSummary, taskStateSnapshot, immediateResponse, userPersistedEarly);
    }

    // 创建一个新的上下文实例，仅更新任务状态快照
    PreparedSessionContext withTaskStateSnapshot(TaskState taskStateSnapshot) {
        return new PreparedSessionContext(sessionKey, session, archivedSummary, taskStateSnapshot, immediateResponse, userPersistedEarly);
    }
}
