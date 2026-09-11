package io.tokenguard.cache;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheKeyTest {

    @Test
    void argumentOrderDoesNotChangeKey() {
        ObjectNode a = Json.obj();
        a.put("pattern", "foo");
        a.put("glob", "**/*.java");
        ObjectNode b = Json.obj();
        b.put("glob", "**/*.java");
        b.put("pattern", "foo");
        assertArrayEquals(CacheKey.compute("t", a, "ws"), CacheKey.compute("t", b, "ws"));
    }

    @Test
    void whitespacePaddingIsNormalized() {
        ObjectNode a = Json.obj();
        a.put("pattern", "  foo ");
        ObjectNode b = Json.obj();
        b.put("pattern", "foo");
        assertArrayEquals(CacheKey.compute("t", a, "ws"), CacheKey.compute("t", b, "ws"));
    }

    @Test
    void caseIsSemanticAndMustDiffer() {
        ObjectNode a = Json.obj();
        a.put("pattern", "Foo");
        ObjectNode b = Json.obj();
        b.put("pattern", "foo");
        assertFalse(java.util.Arrays.equals(
                CacheKey.compute("t", a, "ws"), CacheKey.compute("t", b, "ws")),
                "case-folding would conflate distinct regex searches (§6.1.1 precision rule)");
    }

    @Test
    void workspaceAndToolScopeTheKey() {
        ObjectNode args = Json.obj();
        args.put("pattern", "x");
        assertFalse(java.util.Arrays.equals(
                CacheKey.compute("t", args, "ws1"), CacheKey.compute("t", args, "ws2")));
        assertFalse(java.util.Arrays.equals(
                CacheKey.compute("t1", args, "ws"), CacheKey.compute("t2", args, "ws")));
    }
}
