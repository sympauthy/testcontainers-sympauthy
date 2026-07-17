package com.sympauthy.testcontainers.flow;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonCodecTest {

    @Test
    void parsesScalarsObjectsAndArrays() {
        Map<String, Object> map = JsonCodec.parseObject(
                "{\"s\":\"x\",\"b\":true,\"n\":42,\"f\":1.5,\"nil\":null,\"list\":[1,2],\"obj\":{\"k\":\"v\"}}");

        assertEquals("x", map.get("s"));
        assertEquals(true, map.get("b"));
        assertEquals(42L, map.get("n"), "integral numbers should decode to Long");
        assertEquals(1.5, map.get("f"), "fractional numbers should decode to Double");
        assertNull(map.get("nil"));
        assertEquals(List.of(1L, 2L), map.get("list"));
        assertEquals(Map.of("k", "v"), map.get("obj"));
    }

    @Test
    void writeThenParseRoundTrips() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("login", "a@b.c");
        original.put("count", 3L);
        original.put("nested", Map.of("x", List.of("y")));

        Map<String, Object> back = JsonCodec.parseObject(JsonCodec.write(original));

        assertEquals("a@b.c", back.get("login"));
        assertEquals(3L, back.get("count"));
        assertEquals(Map.of("x", List.of("y")), back.get("nested"));
    }

    @Test
    void escapesAndUnescapesStrings() {
        String json = JsonCodec.write(Map.of("k", "a\"b\\c\nd"));
        assertEquals("a\"b\\c\nd", JsonCodec.parseObject(json).get("k"));
    }
}
