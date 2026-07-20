package ricbot.infra.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonMapUtilsTest {

    @Test
    void copyObjectMap_stringifiesNonNullKeysAndPreservesValues() {
        Map<String, Object> copy = JsonMapUtils.copyObjectMap(Map.of(
                1, "one",
                "nested", Map.of("k", "v")
        ));

        assertEquals("one", copy.get("1"));
        assertEquals(Map.of("k", "v"), copy.get("nested"));
    }

    @Test
    void objectMapConvertersPreserveEmptyVsNullableSemantics() {
        assertTrue(JsonMapUtils.asObjectMap("not-map").isEmpty());
        assertNull(JsonMapUtils.asNullableObjectMap("not-map"));
        assertEquals(Map.of("x", 1), JsonMapUtils.asObjectMap(Map.of("x", 1)));
        assertEquals(Map.of("x", 1), JsonMapUtils.asNullableObjectMap(Map.of("x", 1)));
    }

    @Test
    void objectMapListConvertersFilterNonMapItems() {
        Object raw = List.of(Map.of("a", 1), "skip", Map.of("b", 2));

        assertEquals(List.of(Map.of("a", 1), Map.of("b", 2)), JsonMapUtils.asObjectMapList(raw));
        assertEquals(List.of(Map.of("a", 1), Map.of("b", 2)), JsonMapUtils.asNullableObjectMapList(raw));
        assertTrue(JsonMapUtils.asObjectMapList("not-list").isEmpty());
        assertNull(JsonMapUtils.asNullableObjectMapList("not-list"));
    }
}
