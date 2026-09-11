package io.tokenguard.teamcache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.saas.SaasClient;
import io.tokenguard.util.Crypto;
import io.tokenguard.util.Hashes;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Shared team cache client (§6.2.4): exact-match only. The cloud key is
 * HMAC-SHA256(team_secret, canonical cache key) — meaningless without team
 * membership, uncorrelatable across teams. Payloads are AES-256-GCM encrypted
 * client-side, so the platform stores only ciphertext.
 *
 * A lookup races a short timeout (§1.4/§6.7.1): a slow backend is
 * indistinguishable from a miss and never delays the local miss path.
 */
public final class TeamCache {

    private static final long RACE_TIMEOUT_MS = 150;

    private final SaasClient client;
    private final byte[] hmacSecret;
    private final byte[] aesKey;
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tg-teamcache");
        t.setDaemon(true);
        return t;
    });

    public TeamCache(SaasClient client, byte[] hmacSecret, byte[] aesKey) {
        this.client = client;
        this.hmacSecret = hmacSecret;
        this.aesKey = aesKey;
    }

    /** @return decrypted result text, or empty on miss/tamper/timeout/unreachable. */
    public Optional<String> lookup(byte[] canonicalCacheKey) {
        String hmacHex = hmacKeyHex(canonicalCacheKey);
        Future<Optional<String>> f = io.submit(() -> {
            Optional<JsonNode> entry = client.teamCacheGet(hmacHex, RACE_TIMEOUT_MS);
            if (entry.isEmpty()) return Optional.<String>empty();
            return decrypt(entry.get());
        });
        try {
            return f.get(RACE_TIMEOUT_MS + 50, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return Optional.empty();
        }
    }

    /** Fire-and-forget push (never blocks a tool call). */
    public void push(byte[] canonicalCacheKey, String resultText, long resultTokens) {
        io.submit(() -> {
            try {
                Crypto.Sealed sealed = Crypto.aesGcmEncrypt(aesKey,
                        resultText.getBytes(StandardCharsets.UTF_8));
                ObjectNode body = Json.obj();
                body.put("payload_ciphertext", Base64.getEncoder().encodeToString(sealed.ciphertext()));
                body.put("payload_nonce", Base64.getEncoder().encodeToString(sealed.nonce()));
                body.put("result_tokens", resultTokens);
                client.teamCachePut(hmacKeyHex(canonicalCacheKey), body);
            } catch (Exception e) {
                Log.debug("team-cache push failed: " + e.getMessage());
            }
        });
    }

    private Optional<String> decrypt(JsonNode entry) {
        try {
            byte[] cipher = Base64.getDecoder().decode(entry.get("payload_ciphertext").asText());
            byte[] nonce = Base64.getDecoder().decode(entry.get("payload_nonce").asText());
            byte[] plain = Crypto.aesGcmDecrypt(aesKey, nonce, cipher);
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Tamper or wrong key -> treat as a miss, never surface bad data (§6.2.4)
            Log.debug("team-cache decrypt failed (treated as miss): " + e.getMessage());
            return Optional.empty();
        }
    }

    private String hmacKeyHex(byte[] canonicalCacheKey) {
        return Hashes.hex(Crypto.hmacSha256(hmacSecret, canonicalCacheKey));
    }
}
