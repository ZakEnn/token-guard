package io.tokenguard.cache;

/**
 * Tier-2 embedder (ADR-0003): deterministic bag-of-words random projection.
 * Each lowercased word hashes to a sparse signed pattern in 256 dims; vectors
 * are mean-pooled and L2-normalized. Word reorderings embed identically; a
 * changed identifier in a short query drops cosine far below threshold.
 *
 * This is the fallback for the model2vec-class asset flagged in plan A5 —
 * swap the feature loop for a token-table lookup when the model lands; the
 * int8 storage format and all call sites stay unchanged.
 */
public final class Embedder {

    public static final int DIM = 256;
    private static final int POSITIONS_PER_FEATURE = 8;

    public float[] embed(String text) {
        float[] v = new float[DIM];
        for (String word : text.toLowerCase().split("\\W+")) {
            if (word.isEmpty()) continue;
            long h = fnv64(word);
            for (int k = 0; k < POSITIONS_PER_FEATURE; k++) {
                h = mix(h);
                int pos = (int) Math.floorMod(h, DIM);
                v[pos] += ((h >>> 32) & 1) == 0 ? 1f : -1f;
            }
        }
        normalize(v);
        return v;
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // inputs are unit vectors
    }

    /** int8 quantization for the embeddings table (§4.1). */
    public static byte[] quantize(float[] v) {
        byte[] q = new byte[v.length];
        for (int i = 0; i < v.length; i++)
            q[i] = (byte) Math.max(-127, Math.min(127, Math.round(v[i] * 127f)));
        return q;
    }

    public static float[] dequantize(byte[] q) {
        float[] v = new float[q.length];
        for (int i = 0; i < q.length; i++) v[i] = q[i] / 127f;
        normalize(v);
        return v;
    }

    private static void normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        if (norm == 0) return;
        float inv = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }

    private static long fnv64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static long mix(long z) {
        z += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
