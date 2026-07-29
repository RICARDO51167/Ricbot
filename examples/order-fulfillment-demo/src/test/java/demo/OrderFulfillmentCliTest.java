package demo;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class OrderFulfillmentCliTest {
    @Test void placesOneOrderWithInjectedClock() {
        OrderFulfillmentCli cli = new OrderFulfillmentCli(
                Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC));
        assertEquals("ACCEPTED order-1 2026-01-02T03:04:05Z", cli.place("order-1", "SKU-RED", 2));
    }
}
