package io.tokenguard.cache;

import io.tokenguard.store.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.7 (CLAUDE.md §4.4 debt): eviction under budget pressure — the path
 * exists (CacheStore.evictOverBudget, verified in the 2026-07-19 audit) but
 * had no dedicated test. Asserts space is actually reclaimed under the
 * budget and victims are score-ordered (low value evicted, high value kept).
 */
class CacheEvictionPressureTest {

    @TempDir Path tmp;
    private Database db;

    @BeforeEach void open() throws Exception { db = Database.open(tmp.resolve("guard.db")); }
    @AfterEach void close() { db.close(); }

    private long blobBytes() {
        return db.with(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COALESCE(SUM(length(result_blob)),0) FROM cache_entries")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void evictionReclaimsSpaceAndKeepsHighValueEntries() {
        long budget = 5_000;
        CacheStore store = new CacheStore(db, tmp, budget);
        String kilobyte = "x".repeat(1000);

        // one high-value entry: huge savings + hits (touch bumps hit_count)
        byte[] vip = {42};
        store.store("t", vip, "ws", kilobyte, 10, 1_000_000, 3600, Map.of(), null, null);
        store.lookup(vip);
        store.lookup(vip);

        // pressure: 15 low-value KB entries against a 5 KB budget
        for (int i = 0; i < 15; i++)
            store.store("t", new byte[]{(byte) i, 1}, "ws", kilobyte, 10, 1, 3600,
                    Map.of(), null, null);

        assertTrue(blobBytes() <= budget,
                "eviction must reclaim to within budget, at " + blobBytes());
        assertTrue(store.entryCount() < 16, "entries must actually be evicted");
        assertTrue(store.lookup(vip).isPresent(),
                "score-ordered eviction: the high-value entry must survive the pressure");
    }

    @Test
    void underBudgetNothingIsEvicted() {
        CacheStore store = new CacheStore(db, tmp, 1024 * 1024);
        for (int i = 0; i < 5; i++)
            store.store("t", new byte[]{(byte) i}, "ws", "small", 5, 1, 3600,
                    Map.of(), null, null);
        assertEquals(5, store.entryCount());
    }
}
