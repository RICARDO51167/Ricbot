package demo;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Intentionally small starting point for the full Runtime demonstration. */
public final class OrderFulfillmentCli {
    private final Map<String, Integer> inventory = new LinkedHashMap<>();
    private final Clock clock;

    public OrderFulfillmentCli(Clock clock) {
        this.clock = clock;
        inventory.put("SKU-RED", 10);
        inventory.put("SKU-BLUE", 5);
    }

    public String place(String orderId, String sku, int quantity) {
        int available = inventory.getOrDefault(sku, 0);
        if (quantity <= 0 || available < quantity) return "REJECTED " + orderId;
        inventory.put(sku, available - quantity);
        return "ACCEPTED " + orderId + " " + Instant.now(clock);
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: <order-id> <sku> <quantity>");
            System.exit(2);
        }
        System.out.println(new OrderFulfillmentCli(Clock.systemUTC())
                .place(args[0], args[1], Integer.parseInt(args[2])));
    }
}
