package io.tokenguard.store;

import io.tokenguard.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owner of the single SQLite connection (~/.token-guard/guard.db, plan §4.1).
 *
 * Corruption recovery per §6.7.2: on open failure or failed quick_check the
 * file is renamed aside (one copy kept for support) and a fresh DB is created —
 * the DB is disposable by design; recovery is deletion. TokenGuard must never
 * fail to start because of cache state.
 *
 * Concurrency: one connection guarded by a lock (A11 — worker pool is small,
 * SQLite WAL handles the multi-process case).
 */
public final class Database implements AutoCloseable {

    private final Path dbPath;
    private final Connection conn;
    private final ReentrantLock lock = new ReentrantLock();

    private Database(Path dbPath, Connection conn) {
        this.dbPath = dbPath;
        this.conn = conn;
    }

    public static Database open(Path dbPath) throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
            restrictPermissions(dbPath.getParent());
        } catch (IOException e) {
            throw new SQLException("Cannot create data directory " + dbPath.getParent(), e);
        }
        try {
            return openOnce(dbPath);
        } catch (SQLException first) {
            Log.warn("Database open failed (" + first.getMessage() + "); recovering per §6.7.2");
            moveCorruptAside(dbPath);
            try {
                Database db = openOnce(dbPath);
                Log.warn("Recovered with a fresh database; cache starts cold");
                return db;
            } catch (SQLException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private static Database openOnce(Path dbPath) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        boolean ok = false;
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
            try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1)))
                    throw new SQLException("quick_check failed: database corrupt");
            }
            Schema.migrate(c);
            restrictPermissions(dbPath);
            ok = true;
            return new Database(dbPath, c);
        } finally {
            if (!ok) try { c.close(); } catch (SQLException ignore) {}
        }
    }

    private static void moveCorruptAside(Path dbPath) {
        String stamp = String.valueOf(Instant.now().getEpochSecond());
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Path src = dbPath.resolveSibling(dbPath.getFileName() + suffix);
            if (!Files.exists(src)) continue;
            Path dst = dbPath.resolveSibling(dbPath.getFileName() + suffix + ".corrupt." + stamp);
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Last resort: delete — the DB is disposable (§4.1), starting is not optional
                try { Files.deleteIfExists(src); } catch (IOException ignore) {}
            }
        }
    }

    /** §6.2.1: dir 0700 / file 0600 on POSIX; Windows relies on the profile ACL. */
    private static void restrictPermissions(Path p) {
        try {
            if (Files.getFileStore(p).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                        Files.isDirectory(p) ? "rwx------" : "rw-------"));
            }
        } catch (IOException | UnsupportedOperationException ignore) {
        }
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection conn) throws SQLException;
    }

    public <T> T with(SqlFunction<T> fn) {
        lock.lock();
        try {
            return fn.apply(conn);
        } catch (SQLException e) {
            throw new StoreException("Database operation failed", e);
        } finally {
            lock.unlock();
        }
    }

    public void execute(String sql) {
        with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }

    public Path path() { return dbPath; }

    @Override
    public void close() {
        lock.lock();
        try {
            conn.close();
        } catch (SQLException e) {
            Log.error("Error closing database", e);
        } finally {
            lock.unlock();
        }
    }

    public static final class StoreException extends RuntimeException {
        public StoreException(String msg, Throwable cause) { super(msg, cause); }
    }
}
