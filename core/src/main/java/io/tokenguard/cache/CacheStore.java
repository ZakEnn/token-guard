package io.tokenguard.cache;

import io.tokenguard.store.Database;
import io.tokenguard.util.Hashes;
import io.tokenguard.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DAO for cache_entries/cache_deps/embeddings (§4.1) + the §6.1.3/§6.1.4
 * policies: lazy TTL on read, lazy per-entry dependency validation at hit
 * time, value-density eviction under a size budget. Both tiers (exact, M4
 * semantic) share the same validation path — a semantic hit is held to
 * exactly the same freshness rules as an exact one.
 */
public final class CacheStore {

    public record Hit(long id, String result, long resultTokens, long savedTokens) {}

    private record Row(long id, byte[] blob, long resultTokens, long savedTokens,
                       long createdAt, long ttl) {}

    /** Semantic candidate: entry id + its stored (dequantized) embedding. */
    public record Candidate(long entryId, float[] vector) {}

    /** P4.5b: marker prefix for encrypted blobs (nonce follows, then ciphertext). */
    private static final byte[] ENC_MAGIC = {'T', 'G', 'E', '1'};
    private static final int NONCE_LEN = 12;

    private final Database db;
    private final Path workspaceRoot;
    private final long budgetBytes;
    private final byte[] encryptionKey;   // null = plaintext (default, §6.2.1)

    public CacheStore(Database db, Path workspaceRoot, long budgetBytes) {
        this(db, workspaceRoot, budgetBytes, null);
    }

    /** cache.encrypt=true wiring (P4.5b): AES-256-GCM over result_blob. */
    public CacheStore(Database db, Path workspaceRoot, long budgetBytes, byte[] encryptionKey) {
        this.db = db;
        this.workspaceRoot = workspaceRoot;
        this.budgetBytes = budgetBytes;
        this.encryptionKey = encryptionKey;
    }

    private byte[] encode(String result) {
        byte[] plain = result.getBytes(StandardCharsets.UTF_8);
        if (encryptionKey == null) return plain;
        var sealed = io.tokenguard.util.Crypto.aesGcmEncrypt(encryptionKey, plain);
        byte[] out = new byte[ENC_MAGIC.length + sealed.nonce().length + sealed.ciphertext().length];
        System.arraycopy(ENC_MAGIC, 0, out, 0, ENC_MAGIC.length);
        System.arraycopy(sealed.nonce(), 0, out, ENC_MAGIC.length, sealed.nonce().length);
        System.arraycopy(sealed.ciphertext(), 0, out, ENC_MAGIC.length + sealed.nonce().length,
                sealed.ciphertext().length);
        return out;
    }

    /** @return plaintext, or null when the blob cannot be read under the
     * current key configuration (treated as a miss; the entry is deleted). */
    private String decode(byte[] blob) {
        boolean marked = blob.length > ENC_MAGIC.length + NONCE_LEN
                && blob[0] == 'T' && blob[1] == 'G' && blob[2] == 'E' && blob[3] == '1';
        if (encryptionKey == null)
            return marked ? null : new String(blob, StandardCharsets.UTF_8);
        if (!marked) return new String(blob, StandardCharsets.UTF_8); // pre-encryption entry
        try {
            byte[] nonce = Arrays.copyOfRange(blob, ENC_MAGIC.length, ENC_MAGIC.length + NONCE_LEN);
            byte[] ct = Arrays.copyOfRange(blob, ENC_MAGIC.length + NONCE_LEN, blob.length);
            return new String(io.tokenguard.util.Crypto.aesGcmDecrypt(encryptionKey, nonce, ct),
                    StandardCharsets.UTF_8);
        } catch (Exception wrongKeyOrTampered) {
            return null;
        }
    }

    /** Tier-1 exact lookup with lazy TTL + dependency validation. Empty = miss. */
    public Optional<Hit> lookup(byte[] exactKey) {
        return validate(rowWhere("exact_key = ?", ps -> ps.setBytes(1, exactKey)));
    }

    /** Tier-2: fetch candidates sharing the identity scope (§6.1.1 arg-identity guard). */
    public List<Candidate> semanticCandidates(byte[] identityKey) {
        return db.with(c -> {
            List<Candidate> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e.id, emb.vector FROM cache_entries e "
                            + "JOIN embeddings emb ON e.embedding_id = emb.id "
                            + "WHERE e.identity_key = ?")) {
                ps.setBytes(1, identityKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        out.add(new Candidate(rs.getLong(1), Embedder.dequantize(rs.getBytes(2))));
                }
            }
            return out;
        });
    }

    /** Validate + fetch a semantic candidate by id (same rules as exact tier). */
    public Optional<Hit> hitById(long entryId) {
        return validate(rowWhere("id = ?", ps -> ps.setLong(1, entryId)));
    }

    public void store(String toolName, byte[] exactKey, String workspaceId, String result,
                      long resultTokens, long savedTokens, int ttlSeconds,
                      Map<String, byte[]> deps, byte[] identityKey, float[] embedding) {
        long now = Instant.now().getEpochSecond();
        db.with(c -> {
            Long embeddingId = null;
            if (embedding != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO embeddings(vector) VALUES (?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setBytes(1, Embedder.quantize(embedding));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        embeddingId = keys.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO cache_entries(exact_key, tool_name, result_blob, "
                            + "result_tokens, saved_tokens, embedding_id, identity_key, "
                            + "created_at, last_hit_at, hit_count, ttl_seconds, workspace_id) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,0,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setBytes(1, exactKey);
                ps.setString(2, toolName);
                ps.setBytes(3, encode(result));   // P4.5b: encrypted when configured
                ps.setLong(4, resultTokens);
                ps.setLong(5, savedTokens);
                if (embeddingId == null) ps.setNull(6, java.sql.Types.INTEGER);
                else ps.setLong(6, embeddingId);
                if (identityKey == null) ps.setNull(7, java.sql.Types.BLOB);
                else ps.setBytes(7, identityKey);
                ps.setLong(8, now);
                ps.setLong(9, now);
                ps.setInt(10, ttlSeconds);
                ps.setString(11, workspaceId);
                ps.executeUpdate();
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
                try (PreparedStatement dp = c.prepareStatement(
                        "INSERT OR REPLACE INTO cache_deps(entry_id, file_path, content_hash) "
                                + "VALUES (?,?,?)")) {
                    for (Map.Entry<String, byte[]> dep : deps.entrySet()) {
                        dp.setLong(1, id);
                        dp.setString(2, dep.getKey());
                        dp.setBytes(3, dep.getValue());
                        dp.addBatch();
                    }
                    dp.executeBatch();
                }
            }
            return null;
        });
        sweepExpired(now);
        evictOverBudget(now);
    }

    // ---- shared validation path (§6.1.3 lazy TTL + §6.1.4 dep re-hash) ----

    private Optional<Hit> validate(Row row) {
        if (row == null) return Optional.empty();
        long now = Instant.now().getEpochSecond();
        if (row.createdAt() + row.ttl() < now || !depsStillValid(row.id())) {
            delete(row.id());
            return Optional.empty();
        }
        String result = decode(row.blob());
        if (result == null) {   // undecodable under current key config -> miss
            delete(row.id());
            return Optional.empty();
        }
        touch(row.id(), now);
        return Optional.of(new Hit(row.id(), result, row.resultTokens(), row.savedTokens()));
    }

    private boolean depsStillValid(long entryId) {
        Map<String, byte[]> deps = db.with(c -> {
            Map<String, byte[]> m = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT file_path, content_hash FROM cache_deps WHERE entry_id = ?")) {
                ps.setLong(1, entryId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) m.put(rs.getString(1), rs.getBytes(2));
                }
            }
            return m;
        });
        for (Map.Entry<String, byte[]> dep : deps.entrySet()) {
            Path file = workspaceRoot.resolve(dep.getKey());
            try {
                if (!Files.isRegularFile(file)) return false;
                byte[] hash = Hashes.sha256(Files.readAllBytes(file));
                if (!Arrays.equals(hash, dep.getValue())) return false;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Row rowWhere(String where, Binder binder) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, result_blob, result_tokens, saved_tokens, created_at, ttl_seconds "
                            + "FROM cache_entries WHERE " + where)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Row(rs.getLong(1), rs.getBytes(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6)) : null;
                }
            }
        });
    }

    private void sweepExpired(long now) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM cache_entries WHERE created_at + ttl_seconds < ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM embeddings WHERE id NOT IN "
                        + "(SELECT embedding_id FROM cache_entries WHERE embedding_id IS NOT NULL)");
            }
            return null;
        });
    }

    /** Evict ascending by value-density score until under the size budget (§6.1.3). */
    private void evictOverBudget(long now) {
        record Cand(long id, long bytes, double score) {}
        List<Cand> all = db.with(c -> {
            List<Cand> list = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, length(result_blob), saved_tokens, hit_count, created_at "
                                 + "FROM cache_entries")) {
                while (rs.next()) {
                    double ageHours = Math.max(1.0, (now - rs.getLong(5)) / 3600.0);
                    double score = rs.getLong(3) * (1.0 + rs.getLong(4)) / ageHours;
                    list.add(new Cand(rs.getLong(1), rs.getLong(2), score));
                }
            }
            return list;
        });
        long total = all.stream().mapToLong(Cand::bytes).sum();
        if (total <= budgetBytes) return;
        all.sort((a, b) -> Double.compare(a.score(), b.score()));
        for (Cand victim : all) {
            delete(victim.id());
            total -= victim.bytes();
            Log.debug("evicted cache entry " + victim.id());
            if (total <= budgetBytes) break;
        }
    }

    private void touch(long id, long now) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cache_entries SET last_hit_at = ?, hit_count = hit_count + 1 "
                            + "WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void delete(long id) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM cache_entries WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public long entryCount() {
        return db.with(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM cache_entries")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
