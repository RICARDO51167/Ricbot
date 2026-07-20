package ricbot.domain.message;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class MessageBusTest {

    @Test
    void sendOutbound_blocksWhenQueueIsFull() throws Exception {
        MessageBus bus = new MessageBus();
        for (int i = 0; i < MessageBus.DEFAULT_CAPACITY; i++) {
            bus.sendOutbound(new OutboundMessage());
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocked = executor.submit(() -> bus.sendOutbound(new OutboundMessage()));

            Thread.sleep(100);
            assertFalse(blocked.isDone(), "queue full 时 sendOutbound 应该施加背压");

            assertNotNull(bus.pollOutboundNow());
            blocked.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
