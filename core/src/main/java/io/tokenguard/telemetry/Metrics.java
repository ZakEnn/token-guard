package io.tokenguard.telemetry;

import io.tokenguard.store.Database;
import io.tokenguard.util.Log;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-free counters (plan §6.6.1/§6.6.4): kinds and magnitudes only — no
 * paths, no args, no code ever reach this class. Session counters feed
 * guard_status; rows in metrics_events feed the (M7) daily rollup sync.
 */
public final class Metrics {

    private final Database db;
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong rawTokens = new AtomicLong();
    private final AtomicLong returnedTokens = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    // P2.2: per-tier hit split (exact / semantic / team) for guard_status v2
    private final AtomicLong exactHits = new AtomicLong();
    private final AtomicLong semanticHits = new AtomicLong();
    private final AtomicLong teamHits = new AtomicLong();

    public Metrics(Database db) {
        this.db = db;
    }

    public void toolCall(String tool) {
        toolCalls.incrementAndGet();
        event("tool_call", tool, 1);
    }

    /** raw = estimated tokens of the naive/unoptimized equivalent output. */
    public void tokens(String tool, long raw, long returned) {
        rawTokens.addAndGet(raw);
        returnedTokens.addAndGet(returned);
        event("tokens_raw", tool, raw);
        event("tokens_returned", tool, returned);
    }

    public void cacheHit(String tier) {
        cacheHits.incrementAndGet();
        switch (tier) {
            case "exact" -> exactHits.incrementAndGet();
            case "semantic" -> semanticHits.incrementAndGet();
            case "team" -> teamHits.incrementAndGet();
            default -> { }
        }
        event("cache_hit_" + tier, null, 1);
    }

    /** P2.2 getters: guard_status v2 sections + reconciliation tests. */
    public long savedTokens() { return Math.max(0, rawTokens.get() - returnedTokens.get()); }
    public long rawTokensTotal() { return rawTokens.get(); }
    public long returnedTokensTotal() { return returnedTokens.get(); }

    public void cacheMiss() {
        cacheMisses.incrementAndGet();
        event("cache_miss", null, 1);
    }

    private void event(String kind, String tool, long n) {
        if (n == 0) return;
        try {
            db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO metrics_events(ts, kind, tool, n) VALUES (?,?,?,?)")) {
                    ps.setLong(1, Instant.now().getEpochSecond());
                    ps.setString(2, kind);
                    ps.setString(3, tool);
                    ps.setLong(4, n);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            // Metrics must never break a tool call (§6.7 invariant)
            Log.debug("metrics write failed: " + e.getMessage());
        }
    }

    public String renderSession() {
        long raw = rawTokens.get(), ret = returnedTokens.get();
        long saved = Math.max(0, raw - ret);
        long pct = raw > 0 ? saved * 100 / raw : 0;
        long hits = cacheHits.get(), misses = cacheMisses.get();
        long total = hits + misses;
        return "tool calls: " + toolCalls.get() + "\n"
                + "tokens (" + io.tokenguard.util.TokenEstimator.estimatorName() + "): raw " + raw
                + " -> returned " + ret
                + " (saved ~" + saved + ", " + pct + "%)\n"
                + "cache: " + (total == 0 ? "no cacheable calls yet"
                : hits + "/" + total + " hits (" + (hits * 100 / total) + "%)"
                        + " [exact " + exactHits.get() + ", semantic " + semanticHits.get()
                        + ", team " + teamHits.get() + "]");
    }
}
