package ricbot.infra.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonMapUtils {

    private JsonMapUtils() {
    }

    public static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    public static Map<String, Object> asObjectMap(Object value) {
        return value instanceof Map<?, ?> raw ? copyObjectMap(raw) : new LinkedHashMap<>();
    }

    public static Map<String, Object> asNullableObjectMap(Object value) {
        return value instanceof Map<?, ?> raw ? copyObjectMap(raw) : null;
    }

    public static List<Map<String, Object>> asObjectMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = asNullableObjectMap(item);
            if (map != null) {
                out.add(map);
            }
        }
        return out;
    }

    public static List<Map<String, Object>> asNullableObjectMapList(Object value) {
        if (!(value instanceof List<?>)) {
            return null;
        }
        return asObjectMapList(value);
    }
}
