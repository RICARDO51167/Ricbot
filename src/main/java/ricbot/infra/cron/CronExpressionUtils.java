// 定义包路径，表明该类属于 ricbot.infra.cron 包
package ricbot.infra.cron;

// 导入 cronutils 库中的 Cron 模型类，用于表示解析后的 Cron 表达式
import com.cronutils.model.Cron;
// 导入 CronType 枚举，用于指定 Cron 表达式的类型（如 UNIX, QUARTZ 等）
import com.cronutils.model.CronType;
// 导入 CronDefinition 类，用于定义 Cron 表达式的规则和规范
import com.cronutils.model.definition.CronDefinition;
// 导入 CronDefinitionBuilder 类，用于构建或获取预定义的 Cron 定义
import com.cronutils.model.definition.CronDefinitionBuilder;
// 导入 ExecutionTime 类，用于计算 Cron 表达式的执行时间
import com.cronutils.model.time.ExecutionTime;
// 导入 CronParser 类，用于将字符串形式的 Cron 表达式解析为 Cron 对象
import com.cronutils.parser.CronParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 导入 Java 时间 API 中的 Instant 类，表示时间戳
import java.time.Instant;
// 导入 ZoneId 类，表示时区 ID
import java.time.ZoneId;
// 导入 ZonedDateTime 类，表示带时区的日期和时间
import java.time.ZonedDateTime;
// 导入 Optional 类，用于处理可能为空的结果
import java.util.Optional;

/**
 * Cron 表达式解析辅助类。
 */
public class CronExpressionUtils {
    private static final Logger log = LoggerFactory.getLogger(CronExpressionUtils.class);

    // 定义静态常量 CRON_DEFINITION，使用 Unix 类型的 Cron 定义规范
    private static final CronDefinition CRON_DEFINITION = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
    // 定义静态常量 PARSER，基于上述定义创建 Cron 解析器实例
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
            // 使用解析器将 Cron 表达式字符串解析为 Cron 对象
            Cron cron = PARSER.parse(expression);
            // 根据解析后的 Cron 对象创建 ExecutionTime 实例，用于计算执行时间
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            // 将当前毫秒时间戳转换为指定时区的 ZonedDateTime 对象
            ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone);
            
            // 计算基于当前时间的下一次执行时间，结果封装在 Optional 中
            Optional<ZonedDateTime> nextExecution = executionTime.nextExecution(now);
            // 如果存在下次执行时间，将其转换为毫秒时间戳并返回；否则返回 null
            return nextExecution.map(dateTime -> dateTime.toInstant().toEpochMilli()).orElse(null);
        } catch (Exception e) {
            // 捕获解析或计算过程中的异常，返回 null 表示计算失败
            log.error("CronExpressionUtils: 计算下次执行时间时发生错误", e);
            return null;
        }
    }
}
