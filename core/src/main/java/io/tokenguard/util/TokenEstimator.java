package io.tokenguard.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token counting (plan A6, superseded by enhancement plan P0.1): counts are
 * now exact BPE token counts via JTokKit's cl100k_base encoding (pure Java,
 * vocabulary bundled as a classpath resource). If the encoding fails to
 * initialize (defensive: e.g. a stripped resource in an exotic packaging),
 * we fall back to the historical chars/4 heuristic rather than failing tool
 * calls — TokenGuard must never be the reason the agent fails (plan §6.7).
 *
 * estimateBytes remains a heuristic: byte counts have no text to tokenize.
 */
public final class TokenEstimator {

    /** cl100k_base Encoding is thread-safe and immutable; null => fallback. */
    private static final Encoding ENCODING = initEncoding();
    private static final String NAME = ENCODING != null ? "cl100k_base (exact BPE)" : "chars/4 (fallback)";

    private TokenEstimator() {}

    private static Encoding initEncoding() {
        try {
            return Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        } catch (Throwable t) {
            Log.warn("BPE tokenizer unavailable (" + t + "); falling back to chars/4 estimation");
            return null;
        }
    }

    /** Which counter is active — surfaced in guard_status (P0.1 DoD). */
    public static String estimatorName() {
        return NAME;
    }

    public static long estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        Encoding enc = ENCODING;
        if (enc == null) return (text.length() + 3) / 4;
        return enc.countTokensOrdinary(text);
    }

    public static long estimateBytes(long byteCount) {
        return (byteCount + 3) / 4;
    }
}
