package io.tokenguard.util;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * JDK-only crypto (plan §2): Ed25519 for entitlement tokens (§4.3), HMAC-SHA256
 * for team cache keys and AES-256-GCM for team cache payloads (§6.2.4).
 */
public final class Crypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    // ---- Ed25519 (entitlement token verification, §6.3.1) ----

    public static PublicKey ed25519PublicKey(byte[] x509Encoded) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(x509Encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", e);
        }
    }

    public static boolean ed25519Verify(PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(data);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    // ---- HMAC-SHA256 (anonymized team cache keys, §6.2.4) ----

    public static byte[] hmacSha256(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- AES-256-GCM (team cache payloads, §6.2.4; opt-in local encryption, §6.2.1) ----

    public record Sealed(byte[] nonce, byte[] ciphertext) {}

    public static Sealed aesGcmEncrypt(byte[] key256, byte[] plaintext) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key256, "AES"),
                    new GCMParameterSpec(128, nonce));
            return new Sealed(nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws GeneralSecurityException on tamper or wrong key — callers must treat as a miss. */
    public static byte[] aesGcmDecrypt(byte[] key256, byte[] nonce, byte[] ciphertext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key256, "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher.doFinal(ciphertext);
    }

    // ---- encoding helpers ----

    public static String b64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] b64UrlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
