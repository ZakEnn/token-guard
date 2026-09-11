package io.tokenguard.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal TOML-subset reader for ~/.token-guard/config.toml (plan §5.1):
 * [section] headers, key = value with strings/ints/floats/bools, # comments.
 * Keys are addressed as "section.key". Anything fancier (arrays, tables) is
 * intentionally unsupported — config surface stays small by design.
 */
public final class Config {

    private final Map<String, String> values = new HashMap<>();

    public static Config load(Path file) {
        Config cfg = new Config();
        if (!Files.isRegularFile(file)) return cfg;
        String section = "";
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = stripComment(raw).trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim() + ".";
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = section + line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\""))
                    val = val.substring(1, val.length() - 1);
                cfg.values.put(key, val);
            }
        } catch (IOException e) {
            Log.warn("config unreadable (" + e.getMessage() + "); using defaults");
        }
        return cfg;
    }

    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inString = !inString;
            if (c == '#' && !inString) return line.substring(0, i);
        }
        return line;
    }

    public String getString(String key, String dflt) {
        return values.getOrDefault(key, dflt);
    }

    /** All loaded keys (flattened "section.key") — used by the proxy registry
     * (P1.1) to enumerate [servers.*] blocks without a full TOML table model. */
    public java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(values.keySet());
    }

    public long getLong(String key, long dflt) {
        try {
            return Long.parseLong(values.getOrDefault(key, String.valueOf(dflt)));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public double getDouble(String key, double dflt) {
        try {
            return Double.parseDouble(values.getOrDefault(key, String.valueOf(dflt)));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public boolean getBool(String key, boolean dflt) {
        String v = values.get(key);
        return v == null ? dflt : Boolean.parseBoolean(v);
    }
}
