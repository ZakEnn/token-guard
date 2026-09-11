package io.tokenguard.entitlement;

import com.fasterxml.jackson.databind.JsonNode;
import io.tokenguard.util.Crypto;
import io.tokenguard.util.Json;

import java.security.PublicKey;

/**
 * Parsed, signature-verified entitlement token (plan §4.3). Transport form is
 * "b64url(payload-json).b64url(ed25519-sig)"; the embedded public key is the
 * root of trust for offline operation (§6.3.1).
 */
public record Entitlement(String licenseId, String tier, String teamId, String status,
                          long cacheMb, boolean cloud, boolean audit,
                          long issuedAt, long expiresAt, String rawToken) {

    public static final long GRACE_SECONDS = 72 * 3600;

    /** @return null when the token is malformed, unsigned, or tampered. */
    public static Entitlement verify(String token, PublicKey publicKey) {
        try {
            int dot = token.indexOf('.');
            if (dot <= 0) return null;
            byte[] payload = Crypto.b64UrlDecode(token.substring(0, dot));
            byte[] sig = Crypto.b64UrlDecode(token.substring(dot + 1));
            if (!Crypto.ed25519Verify(publicKey, payload, sig)) return null;
            JsonNode n = Json.MAPPER.readTree(payload);
            JsonNode limits = n.get("limits");
            return new Entitlement(
                    Json.str(n, "lic", null),
                    Json.str(n, "tier", "free"),
                    Json.str(n, "team", null),
                    Json.str(n, "status", "expired"),
                    Json.intVal(limits, "cache_mb", 100),
                    Json.boolVal(limits, "cloud", false),
                    Json.boolVal(limits, "audit", false),
                    n.path("iat").asLong(0),
                    n.path("exp").asLong(0),
                    token);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean activeAt(long epochSeconds) {
        return goodStanding() && epochSeconds < expiresAt;
    }

    /** §6.3.1: expired tokens keep working offline for the grace window. */
    public boolean inGraceAt(long epochSeconds) {
        return goodStanding()
                && epochSeconds >= expiresAt
                && epochSeconds < expiresAt + GRACE_SECONDS;
    }

    private boolean goodStanding() {
        return "active".equals(status) || "trialing".equals(status);
    }
}
