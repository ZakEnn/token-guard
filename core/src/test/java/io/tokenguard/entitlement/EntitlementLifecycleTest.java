package io.tokenguard.entitlement;

import io.tokenguard.saas.SaasClient;
import io.tokenguard.store.Database;
import io.tokenguard.util.Crypto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.junit.jupiter.api.Assertions.*;

/** M6 gate (plan §7 step 13): full lifecycle incl. offline grace and degradation. */
class EntitlementLifecycleTest {

    @TempDir Path dataDir;

    KeyPair keys;
    Database db;
    long now;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        db = Database.open(dataDir.resolve("guard.db"));
        now = 1_800_000_000L;
    }

    @AfterEach
    void tearDown() { db.close(); }

    private String mint(String status, long iat, long exp, String tier, boolean cloud) throws Exception {
        String payload = String.format(
                "{\"lic\":\"lic-1\",\"tier\":\"%s\",\"team\":\"team-1\",\"status\":\"%s\","
                        + "\"limits\":{\"cache_mb\":2048,\"cloud\":%b,\"audit\":false},"
                        + "\"iat\":%d,\"exp\":%d}", tier, status, cloud, iat, exp);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keys.getPrivate());
        sig.update(Crypto.utf8(payload));
        return Crypto.b64Url(Crypto.utf8(payload)) + "." + Crypto.b64Url(sig.sign());
    }

    /** Fake backend: returns a fixed token, or null to simulate unreachable (§6.7.1). */
    private SaasClient fakeBackend(String token) {
        return new SaasClient("http://localhost:1") {
            @Override public String activate(String key, String fp) { return token; }
        };
    }

    private EntitlementManager manager(String licenseKey, SaasClient client) {
        return new EntitlementManager(db, dataDir, keys.getPublic(), licenseKey, client,
                () -> now);
    }

    @Test
    void tokenVerificationRejectsTampering() throws Exception {
        String token = mint("active", now, now + 7 * 86400, "personal", false);
        assertNotNull(Entitlement.verify(token, keys.getPublic()));
        // Forge the payload (tier upgrade) but keep the original signature
        String originalSig = token.substring(token.indexOf('.') + 1);
        String forgedPayload = new String(Crypto.b64UrlDecode(token.substring(0, token.indexOf('.'))))
                .replace("personal", "enterprise");
        String tampered = Crypto.b64Url(Crypto.utf8(forgedPayload)) + "." + originalSig;
        assertNull(Entitlement.verify(tampered, keys.getPublic()));
        assertNull(Entitlement.verify("garbage", keys.getPublic()));
    }

    @Test
    void noLicenseKeyMeansFreeLocalMode() {
        EntitlementManager m = manager(null, null);
        assertEquals(EntitlementManager.Mode.FREE_LOCAL, m.mode());
        assertTrue(m.optimizationsOn(), "free tier still optimizes locally");
        assertFalse(m.cloudOn());
        assertEquals(100, m.cacheMb(512), "free tier cache floor (plan §3 table)");
    }

    @Test
    void activationTransitionsProvisionalToActive() throws Exception {
        String token = mint("active", now, now + 7 * 86400, "team", true);
        EntitlementManager m = manager("KEY", fakeBackend(token));
        assertEquals(EntitlementManager.Mode.PROVISIONAL, m.mode());
        assertTrue(m.optimizationsOn(), "provisional must not punish new users");
        m.refreshOnce();
        assertEquals(EntitlementManager.Mode.ACTIVE, m.mode());
        assertTrue(m.cloudOn());
        assertEquals(2048, m.cacheMb(512));
    }

    @Test
    void expiredTokenEntersGraceThenPassthrough() throws Exception {
        String token = mint("active", now - 8 * 86400, now - 3600, "personal", false);
        EntitlementManager m = manager("KEY", fakeBackend(token));
        m.refreshOnce();
        assertEquals(EntitlementManager.Mode.GRACE, m.mode(), "1h past expiry = grace");
        assertTrue(m.optimizationsOn());

        now += Entitlement.GRACE_SECONDS + 3600; // beyond the 72h grace window
        assertEquals(EntitlementManager.Mode.PASSTHROUGH, m.mode());
        assertFalse(m.optimizationsOn());
    }

    @Test
    void revokedLicenseIsPassthroughImmediately() throws Exception {
        String token = mint("revoked", now, now + 7 * 86400, "personal", false);
        EntitlementManager m = manager("KEY", fakeBackend(token));
        m.refreshOnce();
        assertEquals(EntitlementManager.Mode.PASSTHROUGH, m.mode());
    }

    @Test
    void unreachableBackendKeepsCachedState() throws Exception {
        String token = mint("active", now, now + 7 * 86400, "personal", false);
        EntitlementManager m = manager("KEY", fakeBackend(token));
        m.refreshOnce();
        assertEquals(EntitlementManager.Mode.ACTIVE, m.mode());

        // New manager, unreachable backend: state must come from the cached token (§6.3.1)
        EntitlementManager offline = manager("KEY", fakeBackend(null));
        offline.refreshOnce();
        assertEquals(EntitlementManager.Mode.ACTIVE, offline.mode());
    }

    @Test
    void cacheDbLossDoesNotLoseLicense() throws Exception {
        String token = mint("active", now, now + 7 * 86400, "personal", false);
        manager("KEY", fakeBackend(token)).refreshOnce();
        db.execute("DELETE FROM kv"); // simulate cache-db rebuild (§6.7.2)
        EntitlementManager m = manager("KEY", fakeBackend(null));
        assertEquals(EntitlementManager.Mode.ACTIVE, m.mode(),
                "mirror file must carry the token through cache-db loss");
    }

    @Test
    void installIdIsStable() {
        EntitlementManager m = manager(null, null);
        assertEquals(m.installId(), m.installId());
    }
}
