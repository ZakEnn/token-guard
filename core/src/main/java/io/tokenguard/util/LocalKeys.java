package io.tokenguard.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

/**
 * P4.5b (enhancement plan): local key material for cache-at-rest encryption
 * (plan §6.2.1 opt-in). Decision recorded: the key lives in a permission-
 * restricted file under the data dir — OS keychain integration (DPAPI/
 * Keychain/libsecret) requires platform-native bindings that violate the
 * no-JNI native-image rule (§2) and is deferred; this defends the enterprise
 * "no derived source data unencrypted at rest" checkbox, not a local-admin
 * adversary (the plan's own threat analysis).
 */
public final class LocalKeys {

    private LocalKeys() {}

    public static byte[] loadOrCreate(Path keyFile) throws IOException {
        if (Files.isRegularFile(keyFile)) {
            byte[] key = Files.readAllBytes(keyFile);
            if (key.length == 32) return key;
            throw new IOException("cache key file " + keyFile + " is corrupt (expected 32 bytes, "
                    + "got " + key.length + ") — delete it to re-key (cached entries become "
                    + "unreadable and re-fill naturally)");
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(keyFile.getParent());
        Files.write(keyFile, key);
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException windows) {
            // NTFS: the data dir ACL (Database.restrictPermissions) covers it
        }
        return key;
    }
}
