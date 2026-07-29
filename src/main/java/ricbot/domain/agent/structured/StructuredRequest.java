package ricbot.domain.agent.structured;

import java.util.Map;
import java.util.function.Predicate;

public record StructuredRequest<T>(String name, String description, Map<String, Object> schema,
                                   Class<T> responseType, Predicate<T> validator, int maxRepairCalls) {
    public StructuredRequest {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        schema = Map.copyOf(schema != null ? schema : Map.of());
        if (responseType == null) throw new IllegalArgumentException("responseType is required");
        validator = validator != null ? validator : ignored -> true;
        if (maxRepairCalls < 0 || maxRepairCalls > 1) throw new IllegalArgumentException("maxRepairCalls must be 0 or 1");
    }
}
