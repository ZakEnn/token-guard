package io.tokenguard.telemetry;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.saas.SaasClient;
import io.tokenguard.store.Database;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * §6.6.2: local metrics_events rolled up to daily aggregates and uploaded once
 * per day (and on clean shutdown). Rollups only — structurally incapable of
 * leaking content or per-event timing. Opt-out via config; bounded offline
 * buffer with oldest-first drop.
 */
public final class TelemetrySync {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private static final long MAX_BUFFERED_EVENTS = 100_000; // ~ the 10 MB cap (§6.6.2)

    private final Database db;
    private final SaasClient client;
    private final boolean enabled;
    private final String licenseId;
    private final String deviceId;
    private ScheduledExecutorService scheduler;

    public TelemetrySync(Database db, SaasClient client, boolean enabled,
                         String licenseId, String deviceId) {
        this.db = db;
        this.client = client;
        this.enabled = enabled;
        this.licenseId = licenseId;
        this.deviceId = deviceId;
    }

    public void start() {
        if (!enabled || client == null || licenseId == null) return;
        trimBuffer();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tg-telemetry");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::syncOnce, 1, 24 * 60, TimeUnit.MINUTES);
    }

    public void flushOnShutdown() {
        if (enabled && client != null && licenseId != null) syncOnce();
    }

    void syncOnce() {
        try {
            ArrayNode rollups = buildRollups();
            if (rollups.isEmpty()) return;
            if (client.pushTelemetry(rollups)) {
                markSynced();
                Log.debug("telemetry: pushed " + rollups.size() + " daily rollup(s)");
            }
        } catch (Exception e) {
            Log.debug("telemetry sync failed (buffered locally): " + e.getMessage());
        }
    }

    /** Aggregate unsynced events by (day, kind) into the usage_events shape (§4.2). */
    private ArrayNode buildRollups() {
        record Agg(long toolCalls, long hits, long misses, long saved, long scripts) {}
        return db.with(c -> {
            java.util.Map<String, long[]> byDay = new java.util.TreeMap<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT ts, kind, sum(n) FROM metrics_events WHERE synced = 0 "
                                 + "GROUP BY ts / 86400, kind")) {
                while (rs.next()) {
                    String day = DAY.format(Instant.ofEpochSecond(rs.getLong(1)));
                    String kind = rs.getString(2);
                    long n = rs.getLong(3);
                    long[] a = byDay.computeIfAbsent(day, k -> new long[5]);
                    switch (kind) {
                        case "tool_call" -> a[0] += n;
                        case "tokens_raw" -> a[3] += n;         // provisional; net saved below
                        case "tokens_returned" -> a[3] -= n;
                        case "cache_miss" -> a[2] += n;
                        case "script_run" -> a[4] += n;
                        default -> { if (kind.startsWith("cache_hit_")) a[1] += n; }
                    }
                }
            }
            ArrayNode out = Json.arr();
            byDay.forEach((day, a) -> {
                ObjectNode row = Json.obj();
                row.put("license_id", licenseId);
                row.put("device_id", deviceId);
                row.put("day", day);
                row.put("tool_calls", a[0]);
                row.put("cache_hits", a[1]);
                row.put("cache_misses", a[2]);
                row.put("tokens_saved_est", Math.max(0, a[3]));
                row.put("scripts_run", a[4]);
                out.add(row);
            });
            return out;
        });
    }

    private void markSynced() {
        db.execute("UPDATE metrics_events SET synced = 1 WHERE synced = 0");
        db.execute("DELETE FROM metrics_events WHERE synced = 1");
    }

    private void trimBuffer() {
        long count = db.with(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM metrics_events")) {
                rs.next();
                return rs.getLong(1);
            }
        });
        if (count <= MAX_BUFFERED_EVENTS) return;
        long drop = count - MAX_BUFFERED_EVENTS;
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM metrics_events WHERE id IN "
                            + "(SELECT id FROM metrics_events ORDER BY id LIMIT ?)")) {
                ps.setLong(1, drop);
                ps.executeUpdate();
            }
            return null;
        });
        Log.warn("telemetry buffer over cap; dropped " + drop + " oldest events (§6.6.2)");
    }
}
