package ricbot.domain.agent;

@FunctionalInterface
interface ToolContextApplier {
    void apply(String channel, String chatId, String messageId);
}
