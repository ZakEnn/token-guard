package io.tokenguard.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbedderTest {

    private final Embedder embedder = new Embedder();

    @Test
    void deterministicAndNormalized() {
        float[] a = embedder.embed("find order service methods");
        float[] b = embedder.embed("find order service methods");
        assertArrayEquals(a, b);
        double norm = 0;
        for (float x : a) norm += x * x;
        assertEquals(1.0, norm, 1e-4);
    }

    @Test
    void reorderInvariant() {
        float[] a = embedder.embed("alpha beta gamma");
        float[] b = embedder.embed("gamma alpha beta");
        assertEquals(1.0, Embedder.cosine(a, b), 1e-6);
    }

    @Test
    void changedWordDropsSimilarityBelowThreshold() {
        float[] a = embedder.embed("find users in module alpha");
        float[] b = embedder.embed("find users in module beta");
        assertTrue(Embedder.cosine(a, b) < 0.94,
                "cosine was " + Embedder.cosine(a, b));
    }

    @Test
    void quantizationRoundTripPreservesSimilarity() {
        float[] a = embedder.embed("some moderately long query about code structure");
        float[] restored = Embedder.dequantize(Embedder.quantize(a));
        assertTrue(Embedder.cosine(a, restored) > 0.999);
    }
}
