package ricbot.core.message;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 异步消息总线，用于解耦“消息通道”和“Agent 核心处理逻辑”。
 *
 * <p>设计思想：
 * <ul>
 *     <li>外部聊天通道（如 CLI、Telegram、Discord 等）把用户消息放入入站队列</li>
 *     <li>Agent 从入站队列中消费消息，处理后把响应放入出站队列</li>
 *     <li>外部通道再从出站队列中取出消息并发送给用户</li>
 * </ul>
 *
 * <p>这本质上是一个生产者-消费者模型：
 * <ul>
 *     <li>入站队列：Channel -> Agent</li>
 *     <li>出站队列：Agent -> Channel</li>
 * </ul>
 */
public class MessageBus {

    /**
     * 入站消息队列：
     * 外部通道把用户消息放进来，Agent 从这里取消息处理。
     */
    private final BlockingQueue<InboundMessage> inbound;

    /**
     * 出站消息队列：
     * Agent 处理完后的回复放进来，外部通道从这里取消息发送给用户。
     */
    private final BlockingQueue<OutboundMessage> outbound;

    /**
     * 构造方法，初始化两个阻塞队列。
     *
     * <p>这里使用 LinkedBlockingQueue，原因是：
     * <ul>
     *     <li>线程安全</li>
     *     <li>支持阻塞获取 take()</li>
     *     <li>适合生产者-消费者场景</li>
     * </ul>
     */
    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>();
        this.outbound = new LinkedBlockingQueue<>();
    }

    /**
     * 发布一条入站消息。
     *
     * <p>对应 Python:
     * <pre>
     * async def publish_inbound(self, msg):
     *     await self.inbound.put(msg)
     * </pre>
     *
     * @param msg 来自外部通道的用户消息
     * @throws InterruptedException 如果线程在阻塞时被中断
     */
    public void publishInbound(InboundMessage msg) throws InterruptedException {
        inbound.put(msg);
    }

    /**
     * 消费下一条入站消息。
     *
     * <p>如果当前队列为空，会阻塞等待，直到有新消息进入队列。
     *
     * <p>对应 Python:
     * <pre>
     * async def consume_inbound(self):
     *     return await self.inbound.get()
     * </pre>
     *
     * @return 下一条待处理的入站消息
     * @throws InterruptedException 如果线程在阻塞时被中断
     */
    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }

    /**
     * 发布一条出站消息。
     *
     * <p>对应 Python:
     * <pre>
     * async def publish_outbound(self, msg):
     *     await self.outbound.put(msg)
     * </pre>
     *
     * @param msg Agent 处理完成后的响应消息
     * @throws InterruptedException 如果线程在阻塞时被中断
     */
    public void publishOutbound(OutboundMessage msg) throws InterruptedException {
        outbound.put(msg);
    }

    /**
     * 消费下一条出站消息。
     *
     * <p>如果当前队列为空，会阻塞等待，直到 Agent 产生新的响应。
     *
     * <p>对应 Python:
     * <pre>
     * async def consume_outbound(self):
     *     return await self.outbound.get()
     * </pre>
     *
     * @return 下一条待发送给用户的出站消息
     * @throws InterruptedException 如果线程在阻塞时被中断
     */
    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    /**
     * 获取当前待处理的入站消息数量。
     *
     * <p>对应 Python 的 @property:
     * <pre>
     * @property
     * def inbound_size(self):
     *     return self.inbound.qsize()
     * </pre>
     *
     * @return 当前入站队列中的消息数
     */
    public int getInboundSize() {
        return inbound.size();
    }

    /**
     * 获取当前待发送的出站消息数量。
     *
     * <p>对应 Python 的 @property:
     * <pre>
     * @property
     * def outbound_size(self):
     *     return self.outbound.qsize()
     * </pre>
     *
     * @return 当前出站队列中的消息数
     */
    public int getOutboundSize() {
        return outbound.size();
    }

    /**
     * 获取并移除出站队列中的下一条消息（带超时）。
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return 超时返回 null，否则返回一条消息
     */
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
}
