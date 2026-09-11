package io.tokenguard.entitlement;

import io.tokenguard.saas.SaasClient;
import io.tokenguard.store.Database;
import io.tokenguard.util.Crypto;
import io.tokenguard.util.DeviceId;
import io.tokenguard.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * License state machine (§6.3.1): async validation that NEVER blocks the stdio
 * loop, a 7-day Ed25519-signed entitlement token cached locally, 72 h offline
 * grace, then pass-through-with-warning degradation. The token is mirrored to
 * a separate file so cache-db corruption cannot degrade licensing (§6.7.2).
 */
public final class EntitlementManager {

    public enum Mode {
        FREE_LOCAL,    // no license key configured: local engines, small cache, no cloud
        PROVISIONAL,   // key present, first activation still in flight: optimize, no cloud
        ACTIVE,
        GRACE,         // token expired < 72h and backend unreachable
        PASSTHROUGH    // token long-expired or license revoked/expired
    }

    private static final String KV_TOKEN = "entitlement_token";
    private static final String KV_INSTALL_ID = "install_id";

    private final Database db;
    private final Path mirrorFile;
    private final PublicKey publicKey;
    private final String licenseKey;
    private final SaasClient client;
    private final LongSupplier clock;
    private volatile Entitlement current;
    private ScheduledExecutorService scheduler;

    public EntitlementManager(Database db, Path dataDir, PublicKey publicKey,
                              String licenseKey, SaasClient client, LongSupplier clock) {
        this.db = db;
        this.mirrorFile = dataDir.resolve("entitlement.tok");
        this.publicKey = publicKey;
        this.licenseKey = licenseKey;
        this.client = client;
        this.clock = clock;
        this.current = loadCached();
    }

    /** Startup: serve immediately from cache, refresh in background (§1.4). */
    public void startBackgroundRefresh() {
        if (licenseKey == null || client == null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tg-entitlement");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::refreshOnce, 0, 24, TimeUnit.HOURS);
    }

    void refreshOnce() {
        try {
            String token = client.activate(licenseKey, DeviceId.fingerprint(installId()));
            if (token == null) return; // backend unreachable: keep cached state (§6.7.1)
            Entitlement verified = Entitlement.verify(token, publicKey);
            if (verified == null) {
                Log.warn("backend returned an entitlement token that failed verification");
                return;
            }
            current = verified;
            persist(token);
            Log.info("entitlement refreshed: tier=" + verified.tier()
                    + " status=" + verified.status());
        } catch (Exception e) {
            Log.warn("entitlement refresh failed: " + e.getMessage());
        }
    }

    public Mode mode() {
        if (licenseKey == null) return Mode.FREE_LOCAL;
        Entitlement e = current;
        if (e == null) return Mode.PROVISIONAL;
        long now = clock.getAsLong();
        if (e.activeAt(now)) return Mode.ACTIVE;
        if (e.inGraceAt(now)) return Mode.GRACE;
        return Mode.PASSTHROUGH;
    }

    /** §6.3.1: pass-through disables optimization, never the tools themselves. */
    public boolean optimizationsOn() {
        return mode() != Mode.PASSTHROUGH;
    }

    public boolean cloudOn() {
        Mode m = mode();
        Entitlement e = current;
        return (m == Mode.ACTIVE || m == Mode.GRACE) && e != null && e.cloud();
    }

    public long cacheMb(long configuredDefault) {
        Entitlement e = current;
        Mode m = mode();
        if (m == Mode.ACTIVE || m == Mode.GRACE) return e.cacheMb();
        return Math.min(configuredDefault, 100); // free/provisional/passthrough floor
    }

    public Entitlement entitlement() { return current; }

    public String installId() {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE k = ?")) {
                ps.setString(1, KV_INSTALL_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new String(rs.getBytes(1));
                }
            }
            String id = UUID.randomUUID().toString();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO kv(k, v) VALUES (?, ?)")) {
                ps.setString(1, KV_INSTALL_ID);
                ps.setBytes(2, id.getBytes());
                ps.executeUpdate();
            }
            return id;
        });
    }

    public String renderStatus() {
        Mode m = mode();
        Entitlement e = current;
        StringBuilder sb = new StringBuilder("mode: ").append(m);
        if (e != null) {
            sb.append("; tier: ").append(e.tier())
              .append("; token expires: ").append(Instant.ofEpochSecond(e.expiresAt()));
        }
        if (m == Mode.PASSTHROUGH)
            sb.append(" — optimizations disabled; renew at https://tokenguard.io/account");
        return sb.toString();
    }

    private void persist(String token) {
        try {
            db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)")) {
                    ps.setString(1, KV_TOKEN);
                    ps.setBytes(2, Crypto.utf8(token));
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            Log.warn("could not persist entitlement to kv: " + e.getMessage());
        }
        try {
            Files.writeString(mirrorFile, token);
        } catch (IOException e) {
            Log.warn("could not write entitlement mirror: " + e.getMessage());
        }
    }

    private Entitlement loadCached() {
        String token = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE k = ?")) {
                ps.setString(1, KV_TOKEN);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String(rs.getBytes(1)) : null;
                }
            }
        });
        if (token == null && Files.isRegularFile(mirrorFile)) {
            try {
                token = Files.readString(mirrorFile).strip();
            } catch (IOException ignore) {
            }
        }
        return token == null ? null : Entitlement.verify(token, publicKey);
    }
}
