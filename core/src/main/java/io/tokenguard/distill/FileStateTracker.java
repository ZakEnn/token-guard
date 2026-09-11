package io.tokenguard.distill;

import io.tokenguard.store.Database;
import io.tokenguard.util.Hashes;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks the last content the AGENT has seen per file, per session (plan §1.3
 * Engine 4, table file_state §4.1). Session-scoped by design: a restart resets
 * diff baselines — first re-read returns full content again, which is correct,
 * merely less optimal (§6.7.6).
 */
public final class FileStateTracker {

    private static final long STALE_SECONDS = 7L * 24 * 3600;

    private final Database db;
    private final String sessionId = UUID.randomUUID().toString();

    public FileStateTracker(Database db) {
        this.db = db;
        // Prune long-dead sessions only — concurrent processes (A11) may own recent rows
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM file_state WHERE seen_at < ?")) {
                ps.setLong(1, Instant.now().getEpochSecond() - STALE_SECONDS);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<String> lastSeen(String relPath) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT content FROM file_state WHERE session_id = ? AND path = ?")) {
                ps.setString(1, sessionId);
                ps.setString(2, relPath);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new String(rs.getBytes(1), StandardCharsets.UTF_8))
                            : Optional.<String>empty();
                }
            }
        });
    }

    public void record(String relPath, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] hash = Hashes.sha256(bytes);
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO file_state(session_id, path, content_hash, content, seen_at) "
                            + "VALUES (?,?,?,?,?)")) {
                ps.setString(1, sessionId);
                ps.setString(2, relPath);
                ps.setBytes(3, hash);
                ps.setBytes(4, bytes);
                ps.setLong(5, Instant.now().getEpochSecond());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
