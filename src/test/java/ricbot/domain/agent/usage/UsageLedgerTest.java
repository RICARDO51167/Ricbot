package ricbot.domain.agent.usage;

import org.junit.jupiter.api.Test;
import ricbot.domain.config.ModelCard;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UsageLedgerTest {
    @Test void accumulatesEveryCategoryAndKeepsLegacyView() {
        UsageLedger ledger = UsageLedger.empty()
                .plus(UsageDelta.model("qwen", Map.of("prompt_tokens", 10, "completion_tokens", 4,
                        "total_tokens", 14), 20))
                .plus(UsageDelta.tool(8))
                .plus(UsageDelta.compression("qwen", Map.of("prompt_tokens", 6,
                        "completion_tokens", 2, "total_tokens", 8), 12));
        assertEquals(22, ledger.totalTokens());
        assertEquals(1, ledger.modelCalls());
        assertEquals(1, ledger.compressionCalls());
        assertEquals(1, ledger.toolCalls());
        assertEquals(40, ledger.activeMillis());
        assertEquals(22, ledger.legacyUsage().get("total_tokens"));
    }

    @Test void pricesInMicroUsdWithCeilingAndSaturatesOverflow() {
        ModelCard.Pricing pricing = new ModelCard.Pricing("USD", 1_000_000,
                new BigDecimal("2.5"), new BigDecimal("10"), null);
        UsageDelta priced = UsagePricer.price(new UsageDelta(1, 1, 2, 1, 0, 0, 0,
                0, 0, "qwen", false), pricing);
        assertEquals(13, priced.costMicrousd());
        assertTrue(priced.costKnown());

        UsageLedger saturated = new UsageLedger(Long.MAX_VALUE, 0, Long.MAX_VALUE, 0, 0,
                0, 0, 0, 0, true, Map.of()).plus(new UsageDelta(1, 0, 1,
                0, 0, 0, 0, 0, 0, "", true));
        assertEquals(Long.MAX_VALUE, saturated.totalTokens());
    }
}
