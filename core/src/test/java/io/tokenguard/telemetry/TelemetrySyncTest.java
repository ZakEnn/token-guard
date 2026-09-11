package io.tokenguard.telemetry;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.tokenguard.saas.SaasClient;
import io.tokenguard.store.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TelemetrySyncTest {

    @TempDir Path dataDir;
    Database db;

    static class CapturingClient extends SaasClient {
        ArrayNode lastRollups;
        boolean succeed = true;
        CapturingClient() { super("http://localhost:1"); }
        @Override public boolean pushTelemetry(ArrayNode rollups) {
            this.lastRollups = rollups.deepCopy();
            return succeed;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(dataDir.resolve("guard.db"));
    }

    @AfterEach
    void tearDown() { db.close(); }

    @Test
    void rollupsAggregateByDayAndContainNoContent() {
        Metrics m = new Metrics(db);
        m.toolCall("read_file");
        m.toolCall("read_file");
        m.tokens("read_file", 1000, 200);
        m.cacheHit("exact");
        m.cacheMiss();

        CapturingClient client = new CapturingClient();
        TelemetrySync sync = new TelemetrySync(db, client, true, "lic-1", "dev-1");
        sync.syncOnce();

        assertNotNull(client.lastRollups);
        assertEquals(1, client.lastRollups.size(), "one day of activity");
        var row = client.lastRollups.get(0);
        assertEquals("lic-1", row.get("license_id").asText());
        assertTrue(row.get("tool_calls").asLong() >= 2);
        assertEquals(800, row.get("tokens_saved_est").asLong(), "raw 1000 - returned 200");
        // Content-free by construction: only counters, ids, and a day
        String json = row.toString();
        assertFalse(json.contains("read_file"), "tool NAMES are not in rollups (§6.6.4)");
        assertFalse(json.matches(".*\\.(java|txt|md).*"), "no paths");
    }

    @Test
    void successfulSyncClearsBuffer() {
        new Metrics(db).toolCall("x");
        CapturingClient client = new CapturingClient();
        new TelemetrySync(db, client, true, "lic-1", "dev-1").syncOnce();
        long remaining = db.with(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM metrics_events")) {
                rs.next(); return rs.getLong(1);
            }
        });
        assertEquals(0, remaining, "synced events are cleared");
    }

    @Test
    void failedSyncKeepsBufferForRetry() {
        new Metrics(db).toolCall("x");
        CapturingClient client = new CapturingClient();
        client.succeed = false;
        new TelemetrySync(db, client, true, "lic-1", "dev-1").syncOnce();
        long remaining = db.with(c -> {
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM metrics_events WHERE synced = 0")) {
                rs.next(); return rs.getLong(1);
            }
        });
        assertTrue(remaining >= 1, "unreachable backend must not lose buffered events (§6.7.1)");
    }

    @Test
    void disabledTelemetryNeverUploads() {
        new Metrics(db).toolCall("x");
        CapturingClient client = new CapturingClient();
        TelemetrySync sync = new TelemetrySync(db, client, false, "lic-1", "dev-1");
        sync.start();
        sync.flushOnShutdown();
        assertNull(client.lastRollups, "opt-out means no upload (§6.6.4)");
    }
}
