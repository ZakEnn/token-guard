package io.tokenguard.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTPS client for the Supabase backend (plan §5.3). Every method is designed
 * to be called OFF the stdio path: failures return empty/null and callers keep
 * serving locally (§6.7.1). The license key is exchanged for a short-lived JWT
 * at activation; the key itself never appears in any other request (§6.2.3).
 */
public class SaasClient {

    private final HttpClient http;
    private final String baseUrl;
    private volatile String jwt;

    public SaasClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** @return the signed entitlement token, or null when unreachable/denied. */
    public String activate(String licenseKey, String deviceFingerprint) {
        ObjectNode body = Json.obj();
        body.put("license_key", licenseKey);
        body.put("device_fingerprint", deviceFingerprint);
        body.put("binary_version", io.tokenguard.Main.VERSION);
        body.put("os", System.getProperty("os.name", "unknown"));
        JsonNode resp = post("/v1/activate", body, false);
        if (resp == null) return null;
        String newJwt = Json.str(resp, "jwt", null);
        if (newJwt != null) jwt = newJwt;
        return Json.str(resp, "entitlement_token", null);
    }

    /** Base64 team keys delivered at activation for team tiers (§6.2.4). */
    public Optional<TeamKeys> teamKeys(String licenseKey, String deviceFingerprint) {
        ObjectNode body = Json.obj();
        body.put("license_key", licenseKey);
        body.put("device_fingerprint", deviceFingerprint);
        JsonNode resp = post("/v1/activate", body, false);
        if (resp == null) return Optional.empty();
        String hmac = Json.str(resp, "team_hmac_secret", null);
        String aes = Json.str(resp, "team_cache_key", null);
        if (hmac == null || aes == null) return Optional.empty();
        return Optional.of(new TeamKeys(
                java.util.Base64.getDecoder().decode(hmac),
                java.util.Base64.getDecoder().decode(aes)));
    }

    public record TeamKeys(byte[] hmacSecret, byte[] aesKey) {}

    public boolean pushTelemetry(ArrayNode rollups) {
        ObjectNode body = Json.obj();
        body.set("rollups", rollups);
        return post("/v1/telemetry", body, true) != null;
    }

    /** P4.5c: ship a hash-chained audit batch (§6.6.3, enterprise tier). */
    public boolean postAudit(ArrayNode entries) {
        ObjectNode body = Json.obj();
        body.set("entries", entries);
        return post("/v1/audit", body, true) != null;
    }

    /** GET an encrypted team-cache entry; empty on miss/unreachable/timeout. */
    public Optional<JsonNode> teamCacheGet(String hmacKeyHex, long timeoutMs) {
        try {
            HttpRequest req = authorized(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/team-cache/" + hmacKeyHex))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Optional.empty();
            return Optional.of(Json.MAPPER.readTree(resp.body()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public void teamCachePut(String hmacKeyHex, ObjectNode encryptedEntry) {
        try {
            HttpRequest req = authorized(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/team-cache/" + hmacKeyHex))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(encryptedEntry.toString())))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.debug("team-cache put failed: " + e.getMessage());
        }
    }

    private JsonNode post(String path, ObjectNode body, boolean needsAuth) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (needsAuth) b = authorized(b);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                Log.debug("backend " + path + " -> " + resp.statusCode());
                return null;
            }
            return Json.MAPPER.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.debug("backend " + path + " unreachable: " + e.getMessage());
            return null;
        }
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder b) {
        String token = jwt;
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }
}
