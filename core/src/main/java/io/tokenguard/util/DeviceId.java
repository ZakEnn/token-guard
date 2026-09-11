package io.tokenguard.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Device fingerprint per §6.3.2: sha256(machine id + username + install id) —
 * stable, no PII in the clear. Best-effort per OS; the persisted install id is
 * the floor so a device never fingerprints as a new seat on every start.
 */
public final class DeviceId {

    private DeviceId() {}

    public static String fingerprint(String installId) {
        String machine = machineId();
        String user = System.getProperty("user.name", "");
        return Hashes.hex(Hashes.sha256(machine + "|" + user + "|" + installId));
    }

    private static String machineId() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("linux")) {
                Path p = Path.of("/etc/machine-id");
                if (Files.isRegularFile(p)) return Files.readString(p).strip();
            } else if (os.contains("windows")) {
                Process proc = new ProcessBuilder("reg", "query",
                        "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                        .redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();
                int i = out.indexOf("REG_SZ");
                if (i > 0) return out.substring(i + 6).strip();
            } else if (os.contains("mac")) {
                Process proc = new ProcessBuilder("sh", "-c",
                        "ioreg -rd1 -c IOPlatformExpertDevice | grep IOPlatformUUID")
                        .redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();
                int i = out.indexOf('"');
                if (i >= 0) return out;
            }
        } catch (Exception ignore) {
        }
        return "unknown";
    }
}
