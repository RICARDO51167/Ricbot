package ricbot.domain.agent;

import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.infra.common.HelperUtils;
import ricbot.infra.template.ToolHintFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AgentHookFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentHookFactory.class);

    private final MessageBus bus;
    private final ToolContextApplier toolContextApplier;

    AgentHookFactory(MessageBus bus, ToolContextApplier toolContextApplier) {
        this.bus = bus;
        this.toolContextApplier = toolContextApplier;
    }

    AgentHook create(InboundMessage msg, List<AgentHook> globalHooks, List<AgentHook> requestHooks) {
        AgentHook baseHook = new AgentHook(true) {
            private final StringBuilder streamBuf = new StringBuilder();

            @Override
            public boolean wantsStreaming() {
                Object wants = msg.getMetadata() != null ? msg.getMetadata().get("_wants_stream") : null;
                return wants instanceof Boolean b && b;
            }

            @Override
            public void onStream(AgentHookContext context, String delta) {
                String prevClean = HelperUtils.stripThink(streamBuf.toString());
                streamBuf.append(delta);
                String newClean = HelperUtils.stripThink(streamBuf.toString());
                String incremental = newClean.length() >= prevClean.length()
                        ? newClean.substring(prevClean.length())
                        : newClean;

                if (!incremental.isBlank()) {
                    Map<String, Object> meta = OutboundMessages.copyMetadata(msg);
                    meta.put("_stream_delta", true);
                    OutboundMessage out = OutboundMessages.replyTo(msg, incremental, meta);

                    try {
                        bus.publishOutbound(out);
                    } catch (Exception e) {
                        log.debug("发布流式增量失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
                    }
                }
            }

            @Override
            public void onStreamEnd(AgentHookContext context, boolean resuming) {
                Map<String, Object> meta = OutboundMessages.copyMetadata(msg);
                meta.put("_stream_end", true);
                meta.put("_resuming", resuming);
                OutboundMessage out = OutboundMessages.replyTo(msg, "", meta);

                try {
                    bus.publishOutbound(out);
                } catch (Exception e) {
                    log.debug("发布流式结束标记失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
                }

                streamBuf.setLength(0);
            }

            @Override
            public void beforeExecuteTools(AgentHookContext context) {
                if (!wantsStreaming() && context.getResponse() != null) {
                    String thought = HelperUtils.stripThink(context.getResponse().getContent());
                    if (!thought.isBlank()) {
                        publishProgress(msg, thought, false);
                    }
                }

                String toolHint = HelperUtils.stripThink(ToolHintFormatter.formatToolHints(context.getToolCalls()));
                if (!toolHint.isBlank()) {
                    publishProgress(msg, toolHint, true);
                }

                toolContextApplier.apply(msg.getChannel(), msg.getChatId(), messageIdOf(msg));
            }

            @Override
            public void afterIteration(AgentHookContext context) {
                Map<String, Integer> usage = context.getUsage();
                if (usage != null && !usage.isEmpty()) {
                    log.debug(
                            "LLM 用量: 提示词={} 完成={} 总计={}",
                            usage.getOrDefault("prompt_tokens", 0),
                            usage.getOrDefault("completion_tokens", 0),
                            usage.getOrDefault("total_tokens", 0)
                    );
                }
            }

            @Override
            public String finalizeContent(AgentHookContext context, String content) {
                return HelperUtils.stripThink(content);
            }
        };

        List<AgentHook> hooks = new ArrayList<>();
        hooks.add(baseHook);
        appendHooks(hooks, globalHooks);
        appendHooks(hooks, requestHooks);
        return hooks.size() == 1 ? baseHook : new AgentHook.CompositeHook(hooks);
    }

    private void appendHooks(List<AgentHook> target, List<AgentHook> extraHooks) {
        if (extraHooks == null) {
            return;
        }
        for (AgentHook hook : extraHooks) {
            if (hook != null) {
                target.add(hook);
            }
        }
    }

    private void publishProgress(InboundMessage msg, String content, boolean toolHint) {
        Map<String, Object> meta = OutboundMessages.copyMetadata(msg);
        meta.put("_progress", true);
        meta.put("_tool_hint", toolHint);
        OutboundMessage out = OutboundMessages.replyTo(msg, content, meta);

        try {
            bus.publishOutbound(out);
        } catch (Exception e) {
            log.debug("发布进度消息失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
        }
    }

    private String messageIdOf(InboundMessage msg) {
        if (msg.getMetadata() == null) {
            return null;
        }
        Object value = msg.getMetadata().get("message_id");
        return value != null ? String.valueOf(value) : null;
    }
}
