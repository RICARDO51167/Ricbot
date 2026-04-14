package ricbot.infra.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Cron 表达式解析辅助类。
 */
public class CronExpressionUtils {

    private static final CronDefinition CRON_DEFINITION = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
    private static final CronParser PARSER = new CronParser(CRON_DEFINITION);

    /**
     * 计算下次执行的毫秒数。
     */
    public static Long nextExecutionMillis(String expression, ZoneId zone, long nowMs) {
        try {
            Cron cron = PARSER.parse(expression);
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone);
            
            Optional<ZonedDateTime> nextExecution = executionTime.nextExecution(now);
            return nextExecution.map(dateTime -> dateTime.toInstant().toEpochMilli()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
