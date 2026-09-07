package ricbot.domain.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-free fallback: ceil(UTF-8 bytes/3), protocol overhead, then a 10% safety margin. */
public final class ConservativeContextTokenAccountant implements ContextTokenAccountant {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Override public TokenEstimate count(ModelRequestShape request) {
        long messageBytes = bytes(request.messages());
        long toolBytes = bytes(request.tools());
        long content = ceilDiv(saturatedAdd(messageBytes, toolBytes), 3);
        long messageOverhead = saturatedMultiply(request.messages().size(), 8);
        long toolOverhead = saturatedMultiply(request.tools().size(), 16);
        long beforeMargin = saturatedAdd(content, saturatedAdd(messageOverhead, toolOverhead));
        long margin = ceilDiv(beforeMargin, 10);
        long input = saturatedAdd(beforeMargin, margin);
        long total = saturatedAdd(input, request.outputReserveTokens());
        Map<String, Long> partitions = new LinkedHashMap<>();
        partitions.put("messageContent", ceilDiv(messageBytes, 3));
        partitions.put("toolSchemas", ceilDiv(toolBytes, 3));
        partitions.put("messageOverhead", messageOverhead);
        partitions.put("toolOverhead", toolOverhead);
        partitions.put("safetyMargin", margin);
        partitions.put("reservedOutput", request.outputReserveTokens());
        return new TokenEstimate(TokenEstimate.Mode.ESTIMATED, input, request.outputReserveTokens(), total,
                request.contextWindowTokens(), partitions);
    }

    private static long bytes(Object value) {
        try { return JSON.writeValueAsBytes(value).length; }
        catch (Exception failure) { return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length; }
    }
    private static long ceilDiv(long value, long divisor) {
        return value == 0 ? 0 : 1 + (value - 1) / divisor;
    }
    private static long saturatedAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
    private static long saturatedMultiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
}
