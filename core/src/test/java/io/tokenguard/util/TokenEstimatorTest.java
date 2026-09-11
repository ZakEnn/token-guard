package io.tokenguard.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0.1 DoD (enhancement plan): on a corpus of >=50 real-shaped tool outputs
 * (code / diff / tree / JSON / prose), the estimator is within ±10% of a
 * reference BPE tokenizer count for >=90% of samples. The estimator now
 * delegates to the reference encoding (cl100k_base), so the DoD holds by
 * construction — the assertions below pin both the literal DoD condition and
 * exactness, so a future regression to a heuristic path is caught.
 */
class TokenEstimatorTest {

    /** Checked-in fixture corpus: building blocks mirroring real tool output shapes. */
    private static List<String> fixtures() {
        String java = """
                package io.tokenguard.cache;
                public final class CacheLayer implements ToolRouter.Layer {
                    private final CacheStore store;
                    @Override
                    public ToolResult invoke(Tool tool, ObjectNode args, ToolRouter.Invoker next) throws Exception {
                        byte[] key = CacheKey.compute(tool.name(), args, workspaceId);
                        var exact = store.lookup(key);
                        if (exact.isPresent()) return hit("exact", tool, exact.get());
                        return next.invoke(tool, args);
                    }
                }
                """;
        String diff = """
                --- a/core/src/main/java/io/tokenguard/Main.java
                +++ b/core/src/main/java/io/tokenguard/Main.java
                @@ -24,7 +24,9 @@

                -    private static final String VERSION = "0.1.0";
                +    private static final String VERSION = "0.2.0";
                +    // trace layer wiring (P0.2)
                """;
        String tree = """
                ./
                  .github/
                  backend/
                  core/
                  docs/
                  install/
                  .gitignore
                  CLAUDE.md
                  config.example.toml
                  pom.xml
                """;
        String json = """
                {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"pom.xml: unchanged since you last read it."}],"isError":false}}
                """;
        String prose = "The second read returns a minimal notification instead of the full "
                + "content, which is the primary token optimization for repeated file access "
                + "patterns common in agentic coding workflows.";
        String grep = """
                5 match(es) in 5 file(s):
                core/src/main/java/io/tokenguard/astindex/QueryEngine.java
                  19: public final class QueryEngine {
                core/src/main/java/io/tokenguard/mcp/ToolRouter.java
                  22: public final class ToolRouter {
                """;
        String outline = """
                core/src/main/java/io/tokenguard/Main.java
                  class io.tokenguard.Main  (19-93)
                    field String VERSION  (24-24)
                    method public static void main(String[] args)  (44-47)
                    method private Path resolveDataDir()  (83-92)
                """;
        String unicode = "Précision: réduction des tokens — 96 % (estimation) → vérifiée ✓";
        String[] blocks = {java, diff, tree, json, prose, grep, outline, unicode};

        List<String> out = new ArrayList<>();
        // 8 raw blocks + pairwise concatenations and repetitions => 56 samples
        for (String b : blocks) out.add(b);
        for (int i = 0; i < blocks.length; i++)
            for (int j = 0; j < blocks.length; j += 2)
                out.add(blocks[i] + "\n" + blocks[j]);
        for (String b : new String[]{java, json}) {
            out.add(b.repeat(3));
            out.add(b.repeat(8));
        }
        for (String b : blocks) out.add(b.toUpperCase());
        return out;
    }

    @Test
    void bpeCountingMatchesReferenceWithinDoDBounds() {
        Encoding reference = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        List<String> corpus = fixtures();
        assertTrue(corpus.size() >= 50, "DoD requires >=50 samples, got " + corpus.size());

        int withinTenPct = 0;
        for (String sample : corpus) {
            long ref = reference.countTokensOrdinary(sample);
            long est = TokenEstimator.estimate(sample);
            assertEquals(ref, est, "estimator must be exact under the BPE path");
            if (Math.abs(est - ref) <= Math.max(1, ref / 10)) withinTenPct++;
        }
        assertTrue(withinTenPct * 100 >= corpus.size() * 90,
                "DoD: >=90% of samples within ±10% (got " + withinTenPct + "/" + corpus.size() + ")");
    }

    @Test
    void estimatorReportsActiveCounter() {
        assertTrue(TokenEstimator.estimatorName().contains("cl100k_base"),
                "guard_status must be able to name the active estimator");
    }

    @Test
    void nullAndEmptyAreZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void bpeDivergesFromCharsBy4OnCode() {
        // Documents why P0.1 matters: chars/4 materially misestimates code-shaped text.
        String code = fixtures().get(0);
        long chars4 = (code.length() + 3) / 4;
        long bpe = TokenEstimator.estimate(code);
        assertNotEquals(chars4, bpe, "if these ever coincide the fixture is degenerate");
    }

    @Test
    void byteEstimateRemainsHeuristic() {
        assertEquals(1, TokenEstimator.estimateBytes(1));
        assertEquals(25, TokenEstimator.estimateBytes(100));
    }
}
