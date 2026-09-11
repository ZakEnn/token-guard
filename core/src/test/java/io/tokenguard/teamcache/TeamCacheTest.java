package io.tokenguard.teamcache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.saas.SaasClient;
import io.tokenguard.util.Hashes;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TeamCacheTest {

    /** In-memory fake backend keyed by hmac-hex; simulates the server that sees only ciphertext. */
    static class FakeBackend extends SaasClient {
        final Map<String, ObjectNode> store = new HashMap<>();
        FakeBackend() { super("http://localhost:1"); }
        @Override public void teamCachePut(String hmacKeyHex, ObjectNode entry) {
            store.put(hmacKeyHex, entry.deepCopy());
        }
        @Override public Optional<JsonNode> teamCacheGet(String hmacKeyHex, long timeoutMs) {
            return Optional.ofNullable(store.get(hmacKeyHex));
        }
    }

    private byte[] rand(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    @Test
    void roundTripEncryptsAndDecrypts() {
        FakeBackend backend = new FakeBackend();
        byte[] hmac = rand(32), aes = rand(32);
        TeamCache tc = new TeamCache(backend, hmac, aes);
        byte[] key = Hashes.sha256("some canonical cache key");

        tc.push(key, "the cached result text", 42);
        // push is async; poll briefly
        Optional<String> got = pollLookup(tc, key);
        assertTrue(got.isPresent());
        assertEquals("the cached result text", got.get());
    }

    @Test
    void serverNeverSeesPlaintext() {
        FakeBackend backend = new FakeBackend();
        TeamCache tc = new TeamCache(backend, rand(32), rand(32));
        byte[] key = Hashes.sha256("k");
        tc.push(key, "PROPRIETARY SECRET CODE", 10);
        pollLookup(tc, key);
        for (ObjectNode entry : backend.store.values()) {
            String cipher = entry.get("payload_ciphertext").asText();
            String decoded = new String(java.util.Base64.getDecoder().decode(cipher));
            assertFalse(decoded.contains("PROPRIETARY"), "payload must be encrypted at rest (§6.2.4)");
        }
        assertFalse(backend.store.keySet().iterator().next().contains("k"),
                "cloud key is an HMAC, not the raw cache key");
    }

    @Test
    void differentTeamSecretCannotDecrypt() {
        FakeBackend backend = new FakeBackend();
        byte[] key = Hashes.sha256("k");
        new TeamCache(backend, rand(32), rand(32)).push(key, "secret", 1);
        pollSettle();
        // A different team's keys: HMAC differs so it's a plain miss (correct partitioning)
        TeamCache other = new TeamCache(backend, rand(32), rand(32));
        assertTrue(other.lookup(key).isEmpty());
    }

    @Test
    void wrongAesKeyIsTreatedAsMissNotError() {
        FakeBackend backend = new FakeBackend();
        byte[] hmac = rand(32);
        byte[] key = Hashes.sha256("k");
        new TeamCache(backend, hmac, rand(32)).push(key, "secret", 1);
        pollSettle();
        // Same HMAC (entry found) but wrong AES key -> GCM tag fails -> miss, no crash
        TeamCache wrongAes = new TeamCache(backend, hmac, rand(32));
        assertTrue(wrongAes.lookup(key).isEmpty());
    }

    private Optional<String> pollLookup(TeamCache tc, byte[] key) {
        for (int i = 0; i < 50; i++) {
            Optional<String> r = tc.lookup(key);
            if (r.isPresent()) return r;
            pollSettle();
        }
        return tc.lookup(key);
    }

    private void pollSettle() {
        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
    }
}
