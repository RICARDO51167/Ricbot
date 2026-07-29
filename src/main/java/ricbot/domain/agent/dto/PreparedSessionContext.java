package ricbot.domain.agent.dto;

import ricbot.domain.agent.TaskState;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

/**
 * @param sessionKey         会话的唯一标识键
 * @param session            会话对象，包含会话的核心信息
 * @param archivedSummary    归档的会话摘要信息
 * @param taskStateSnapshot  任务状态的快照，用于记录当前任务的执行状态
 * @param immediateResponse  需要立即发送的出站消息
 * @param userPersistedEarly 标记用户是否已提前持久化会话状态
 */ // 准备会话上下文，用于封装会话处理过程中的关键状态和数据
public record PreparedSessionContext(String sessionKey, Session session, String archivedSummary, TaskState taskStateSnapshot,
                              OutboundMessage immediateResponse, boolean userPersistedEarly) {

    // 构造函数，初始化所有最终字段

    // 创建一个新的上下文实例，仅更新用户提前持久化的标志
    public PreparedSessionContext withUserPersistedEarly() {
        return new PreparedSessionContext(sessionKey, session, archivedSummary, taskStateSnapshot, immediateResponse, true);
    }

    // 创建一个新的上下文实例，仅更新任务状态快照
    public PreparedSessionContext withTaskStateSnapshot(TaskState taskStateSnapshot) {
        return new PreparedSessionContext(sessionKey, session, archivedSummary, taskStateSnapshot, immediateResponse, userPersistedEarly);
    }
}
