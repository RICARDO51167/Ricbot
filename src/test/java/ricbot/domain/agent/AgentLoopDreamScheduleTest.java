package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import ricbot.infra.config.Config;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopDreamScheduleTest {

    @Test
    void computeDreamDelayMillis_usesConfiguredCronAndTimezone() {
        Config.DreamConfig dream = new Config.DreamConfig();
        dream.setCron("0 3 * * *");

        long now = Instant.parse("2026-04-26T02:30:00Z").toEpochMilli();

        assertEquals(
                TimeUnit.MINUTES.toMillis(30),
                AgentLoop.computeDreamDelayMillis(dream, "UTC", now)
        );
    }

    @Test
    void computeDreamDelayMillis_fallsBackWhenCronIsBlank() {
        Config.DreamConfig dream = new Config.DreamConfig();
        dream.setCron(" ");

        long now = Instant.parse("2026-04-26T02:30:00Z").toEpochMilli();

        assertEquals(
                TimeUnit.MINUTES.toMillis(15),
                AgentLoop.computeDreamDelayMillis(dream, "UTC", now)
        );
    }

    @Test
    void computeDreamDelayMillis_fallsBackWhenCronIsInvalid() {
        Config.DreamConfig dream = new Config.DreamConfig();
        dream.setCron("not a cron");

        long now = Instant.parse("2026-04-26T02:30:00Z").toEpochMilli();

        assertEquals(
                TimeUnit.MINUTES.toMillis(15),
                AgentLoop.computeDreamDelayMillis(dream, "UTC", now)
        );
    }
}
