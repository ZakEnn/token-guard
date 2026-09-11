package io.tokenguard.store;

import io.tokenguard.cache.CacheStore;
import io.tokenguard.telemetry.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.7 (assumption A11): two IDE windows = two TokenGuard processes sharing
 * one guard.db. Modeled here as two Database instances (two SQLite
 * connections) on the same file — WAL + busy_timeout must make concurrent
 * use safe, and one instance's writes must be visible to the other.
 */
class SharedDatabaseTest {

    @TempDir Path tmp;

    @Test
    void writesFromOneInstanceAreVisibleToTheOther() throws Exception {
        Path file = tmp.resolve("guard.db");
        try (Database db1 = Database.open(file); Database db2 = Database.open(file)) {
            CacheStore store1 = new CacheStore(db1, tmp, 1024 * 1024);
            CacheStore store2 = new CacheStore(db2, tmp, 1024 * 1024);
            byte[] key = {5, 5};
            store1.store("t", key, "ws", "written-by-instance-1", 5, 2, 3600,
                    Map.of(), null, null);
            assertEquals("written-by-instance-1",
                    store2.lookup(key).orElseThrow().result(),
                    "cache is shared across instances (A11)");
        }
    }

    @Test
    void concurrentMetricsWritesFromTwoInstancesLoseNothing() throws Exception {
        Path file = tmp.resolve("guard.db");
        try (Database db1 = Database.open(file); Database db2 = Database.open(file)) {
            Metrics m1 = new Metrics(db1);
            Metrics m2 = new Metrics(db2);
            int perWriter = 150;
            CountDownLatch start = new CountDownLatch(1);
            Thread t1 = writer(m1, "a", perWriter, start);
            Thread t2 = writer(m2, "b", perWriter, start);
            t1.start();
            t2.start();
            start.countDown();
            t1.join(30_000);
            t2.join(30_000);
            assertFalse(t1.isAlive() || t2.isAlive(), "writers must not deadlock");

            long rows = db1.with(c -> {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT COUNT(*) FROM metrics_events WHERE kind = 'tool_call'")) {
                    rs.next();
                    return rs.getLong(1);
                }
            });
            assertEquals(2L * perWriter, rows,
                    "busy_timeout must absorb contention — no lost writes");
        }
    }

    private static Thread writer(Metrics m, String tool, int n, CountDownLatch start) {
        Thread t = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < n; i++) m.toolCall(tool);
        }, "tg-shared-writer-" + tool);
        t.setDaemon(true);
        return t;
    }
}
