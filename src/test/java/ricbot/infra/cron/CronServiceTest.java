package ricbot.infra.cron;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.cron.CronTypes.CronJob;
import ricbot.infra.cron.CronTypes.CronPayload;
import ricbot.infra.cron.CronTypes.CronSchedule;
import ricbot.infra.cron.CronTypes.ScheduleKind;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class CronServiceTest {

    @Test
    void testCronExecution(@TempDir Path tempDir) throws Exception {
        Path storePath = tempDir.resolve("cron.json");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> executedJobName = new AtomicReference<>();
        
        CronService service = new CronService(storePath, job -> {
            executedJobName.set(job.getName());
            latch.countDown();
            return "ok";
        }, 100);

        // Add a job that runs "now" (AT with current timestamp)
        // For this test, let's just trigger onTimer manually or use start() with pre-populated file
        java.nio.file.Files.writeString(storePath, "{\"version\":1, \"jobs\":[" + 
            "{\"id\":\"j1\", \"name\":\"Test\", \"enabled\":true, \"schedule\":{\"kind\":\"AT\", \"at_ms\":" + (System.currentTimeMillis() + 200) + "}, \"payload\":{\"message\":\"hi\"}}" +
            "]}");
        
        service.start();
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Job should have executed");
            assertEquals("Test", executedJobName.get());
        } finally {
            service.stop();
        }
    }
}
