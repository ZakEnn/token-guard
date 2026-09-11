package io.tokenguard.cache;

import io.tokenguard.store.Database;
import io.tokenguard.util.LocalKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.5b DoD: with cache.encrypt on, result blobs at rest are ciphertext (no
 * plaintext bytes in the DB file rows); lookups round-trip; the same key file
 * reopens the cache across restarts; a wrong/absent key degrades to misses,
 * never to garbage or crashes.
 */
class CacheEncryptionTest {

    private static final String SECRET_RESULT =
            "public class ProprietaryAlgo { /* the secret sauce 12345 */ }";

    @TempDir Path tmp;
    private Database db;
    private byte[] key;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(tmp.resolve("guard.db"));
        key = LocalKeys.loadOrCreate(tmp.resolve("cache.key"));
    }

    @AfterEach void tearDown() { db.close(); }

    private byte[] rawBlob() {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT result_blob FROM cache_entries LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        });
    }

    @Test
    void blobsAreCiphertextAtRestAndRoundTrip() throws Exception {
        CacheStore store = new CacheStore(db, tmp, 100 * 1024 * 1024, key);
        byte[] exactKey = new byte[]{1, 2, 3};
        store.store("t", exactKey, "ws", SECRET_RESULT, 10, 5, 3600, Map.of(), null, null);

        byte[] raw = rawBlob();
        assertNotNull(raw);
        assertEquals('T', raw[0]);
        assertEquals('1', raw[3]);
        String rawAscii = new String(raw, StandardCharsets.ISO_8859_1);
        assertFalse(rawAscii.contains("ProprietaryAlgo"), "no plaintext at rest");
        assertFalse(rawAscii.contains("secret sauce"), "no plaintext at rest");

        Optional<CacheStore.Hit> hit = store.lookup(exactKey);
        assertTrue(hit.isPresent());
        assertEquals(SECRET_RESULT, hit.get().result(), "round-trip must be exact");

        // restart with the same key file
        byte[] reloaded = LocalKeys.loadOrCreate(tmp.resolve("cache.key"));
        assertArrayEquals(key, reloaded, "key file must be stable across restarts");
        CacheStore reopened = new CacheStore(db, tmp, 100 * 1024 * 1024, reloaded);
        assertEquals(SECRET_RESULT, reopened.lookup(exactKey).orElseThrow().result());
    }

    @Test
    void wrongOrAbsentKeyDegradesToMissNeverGarbage() {
        CacheStore store = new CacheStore(db, tmp, 100 * 1024 * 1024, key);
        byte[] exactKey = new byte[]{9, 9};
        store.store("t", exactKey, "ws", SECRET_RESULT, 10, 5, 3600, Map.of(), null, null);

        // absent key (encrypt turned off later): encrypted blob is a clean miss
        CacheStore plaintextStore = new CacheStore(db, tmp, 100 * 1024 * 1024, null);
        assertTrue(plaintextStore.lookup(exactKey).isEmpty(),
                "encrypted entry without a key must be a miss, not mojibake");

        // wrong key: authenticated decryption fails -> miss (entry deleted)
        store.store("t", exactKey, "ws", SECRET_RESULT, 10, 5, 3600, Map.of(), null, null);
        byte[] wrong = new byte[32];
        CacheStore wrongStore = new CacheStore(db, tmp, 100 * 1024 * 1024, wrong);
        assertTrue(wrongStore.lookup(exactKey).isEmpty());
    }

    @Test
    void preEncryptionEntriesRemainReadableAfterEnablingEncryption() {
        CacheStore plain = new CacheStore(db, tmp, 100 * 1024 * 1024, null);
        byte[] exactKey = new byte[]{7};
        plain.store("t", exactKey, "ws", "old plaintext entry", 5, 2, 3600, Map.of(), null, null);
        CacheStore encrypted = new CacheStore(db, tmp, 100 * 1024 * 1024, key);
        assertEquals("old plaintext entry", encrypted.lookup(exactKey).orElseThrow().result(),
                "turning encryption on must not orphan the existing cache");
    }
}
