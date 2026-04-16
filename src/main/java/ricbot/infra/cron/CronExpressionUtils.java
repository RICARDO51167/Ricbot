package ricbot.infra.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Cron 表达式工具类
 */
public class CronExpressionUtils {
    private static final Logger log = LoggerFactory.getLogger(CronExpressionUtils.class);

    private static final CronDefinition CRON_DEFINITION = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
    private static final CronParser PARSER = new CronParser(CRON_DEFINITION);

    /**
     * 计算下次执行的毫秒数。
     *
     * @param expression Cron 表达式字符串
     * @param zone       时区
     * @param nowMs      当前时间的毫秒时间戳
     * @return 下次执行时间的毫秒时间戳，如果无法计算则返回 null
     */
    public static Long nextExecutionMillis(String expression, ZoneId zone, long nowMs) {
        try {
            Cron cron = PARSER.parse(expression);
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone);
            
            Optional<ZonedDateTime> nextExecution = executionTime.nextExecution(now);
            return nextExecution.map(dateTime -> dateTime.toInstant().toEpochMilli()).orElse(null);
        } catch (Exception e) {
            log.error("CronExpressionUtils: 计算下次执行时间时发生错误", e);
            return null;
        }
    }
}
