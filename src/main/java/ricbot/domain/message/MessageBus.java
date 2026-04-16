package ricbot.domain.message;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 异步消息总线
 */
public class MessageBus {

    private final BlockingQueue<InboundMessage> inbound;

    private final BlockingQueue<OutboundMessage> outbound;

    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>();
        this.outbound = new LinkedBlockingQueue<>();
    }

    public void publishInbound(InboundMessage msg) throws InterruptedException {
        inbound.put(msg);
    }

    public void publishOutbound(OutboundMessage msg) throws InterruptedException {
        outbound.put(msg);
    }

    public OutboundMessage pollOutbound(int timeoutMs) {
        try {
            return outbound.poll(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public InboundMessage consumeInbound(int i, TimeUnit timeUnit) throws InterruptedException {
        return inbound.poll(i, timeUnit);
    }

    public void sendOutbound(OutboundMessage msg) {
        outbound.offer(msg);
    }

    public OutboundMessage pollOutboundNow() {
        return outbound.poll();
    }
}
