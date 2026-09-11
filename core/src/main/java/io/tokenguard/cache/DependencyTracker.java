package io.tokenguard.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures the exact set of (workspace-relative path, content hash) a tool read
 * while producing a result (§6.1.4). Engines already know their read set —
 * they call recordFile(); the cache layer brackets collection around each
 * cacheable invocation. Thread-confined: tool calls are serialized (A11).
 */
public final class DependencyTracker {

    private final ThreadLocal<Map<String, byte[]>> current = new ThreadLocal<>();

    public void begin() {
        current.set(new LinkedHashMap<>());
    }

    public Map<String, byte[]> end() {
        Map<String, byte[]> deps = current.get();
        current.remove();
        return deps == null ? Map.of() : deps;
    }

    /** No-op unless a cacheable call is in flight. */
    public void recordFile(String relPath, byte[] contentHash) {
        Map<String, byte[]> deps = current.get();
        if (deps != null) deps.put(relPath, contentHash);
    }
}
