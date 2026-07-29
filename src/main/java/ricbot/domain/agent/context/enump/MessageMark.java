package ricbot.domain.agent.context.enump;

/**
 * 消息标记枚举，用于标识消息的不同状态或处理需求。
 */
public enum MessageMark {
    /**
     * 表示消息处于活跃状态，需要正常处理和响应。
     */
    ACTIVE,

    /**
     * 表示消息已被压缩，可能需要在处理前进行解压或特殊处理。
     */
    COMPRESSED,

    /**
     * 表示消息被保留，通常用于归档、延迟处理或后续分析。
     */
    PRESERVED
}
