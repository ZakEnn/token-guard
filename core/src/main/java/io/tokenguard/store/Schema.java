package io.tokenguard.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Local schema per plan §4.1. Versioned via kv('schema_version'). */
final class Schema {

    static final int CURRENT_VERSION = 3;

    private static final String[] DDL_V1 = {
        """
        CREATE TABLE IF NOT EXISTS kv (
          k TEXT PRIMARY KEY,
          v BLOB
        )""",
        """
        CREATE TABLE IF NOT EXISTS embeddings (
          id     INTEGER PRIMARY KEY,
          vector BLOB NOT NULL
        )""",
        """
        CREATE TABLE IF NOT EXISTS cache_entries (
          id            INTEGER PRIMARY KEY,
          exact_key     BLOB NOT NULL UNIQUE,
          tool_name     TEXT NOT NULL,
          result_blob   BLOB NOT NULL,
          result_tokens INTEGER NOT NULL,
          saved_tokens  INTEGER NOT NULL,
          embedding_id  INTEGER REFERENCES embeddings(id),
          identity_key  BLOB,
          created_at    INTEGER NOT NULL,
          last_hit_at   INTEGER NOT NULL,
          hit_count     INTEGER NOT NULL DEFAULT 0,
          ttl_seconds   INTEGER NOT NULL,
          workspace_id  TEXT NOT NULL
        )""",
        "CREATE INDEX IF NOT EXISTS idx_cache_ws_tool ON cache_entries(workspace_id, tool_name)",
        // identity_key scopes Tier-2 semantic candidates: same tool + workspace +
        // identity (non-free-text) args (§6.1.1 arg-identity guard)
        "CREATE INDEX IF NOT EXISTS idx_cache_identity ON cache_entries(identity_key)",
        """
        CREATE TABLE IF NOT EXISTS cache_deps (
          entry_id     INTEGER NOT NULL REFERENCES cache_entries(id) ON DELETE CASCADE,
          file_path    TEXT NOT NULL,
          content_hash BLOB NOT NULL,
          PRIMARY KEY (entry_id, file_path)
        )""",
        """
        CREATE TABLE IF NOT EXISTS indexed_files (
          id           INTEGER PRIMARY KEY,
          workspace_id TEXT NOT NULL,
          path         TEXT NOT NULL,
          content_hash BLOB NOT NULL,
          mtime        INTEGER NOT NULL,
          size         INTEGER NOT NULL,
          parse_status TEXT NOT NULL,
          parse_error  TEXT,
          UNIQUE (workspace_id, path)
        )""",
        """
        CREATE TABLE IF NOT EXISTS symbols (
          id          INTEGER PRIMARY KEY,
          file_id     INTEGER NOT NULL REFERENCES indexed_files(id) ON DELETE CASCADE,
          kind        TEXT NOT NULL,
          fq_name     TEXT NOT NULL,
          signature   TEXT,
          start_line  INTEGER NOT NULL,
          end_line    INTEGER NOT NULL,
          doc_summary TEXT
        )""",
        "CREATE INDEX IF NOT EXISTS idx_symbols_fq ON symbols(fq_name)",
        """
        CREATE TABLE IF NOT EXISTS symbol_edges (
          from_id INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,
          to_fq   TEXT NOT NULL,
          kind    TEXT NOT NULL,
          PRIMARY KEY (from_id, to_fq, kind)
        )""",
        "CREATE INDEX IF NOT EXISTS idx_edges_to ON symbol_edges(to_fq)",
        """
        CREATE TABLE IF NOT EXISTS file_state (
          session_id   TEXT NOT NULL,
          path         TEXT NOT NULL,
          content_hash BLOB NOT NULL,
          content      BLOB NOT NULL,
          seen_at      INTEGER NOT NULL,
          PRIMARY KEY (session_id, path)
        )""",
        """
        CREATE TABLE IF NOT EXISTS metrics_events (
          id     INTEGER PRIMARY KEY,
          ts     INTEGER NOT NULL,
          kind   TEXT NOT NULL,
          tool   TEXT,
          n      INTEGER NOT NULL DEFAULT 1,
          synced INTEGER NOT NULL DEFAULT 0
        )"""
    };

    // v2 (enhancement plan P0.2): interaction trace — the per-client verification
    // substrate. Unconditional (even passthrough/error rows) per
    // PLATFORM_AGNOSTIC_PLAN §1.5; own table, no FK coupling to cache/index
    // (reconciled Deviation 2: separate concern, same store).
    private static final String[] DDL_V2 = {
        """
        CREATE TABLE IF NOT EXISTS interaction_trace (
          id              INTEGER PRIMARY KEY,
          session_id      TEXT NOT NULL,
          ts              INTEGER NOT NULL,
          client_name     TEXT,
          client_version  TEXT,
          tool            TEXT NOT NULL,
          engine          TEXT NOT NULL,
          raw_tokens      INTEGER NOT NULL DEFAULT 0,
          returned_tokens INTEGER NOT NULL DEFAULT 0,
          was_error       INTEGER NOT NULL DEFAULT 0,
          was_passthrough INTEGER NOT NULL DEFAULT 0,
          optimization    TEXT
        )""",
        "CREATE INDEX IF NOT EXISTS idx_trace_session ON interaction_trace(session_id, ts)"
    };

    // v3 (enhancement plan P3.3): rolling session journal — compaction
    // recovery. Local-only (paths never leave the machine, §6.6.4); keyed by
    // workspace so a fresh process/initialize can restore prior state.
    private static final String[] DDL_V3 = {
        """
        CREATE TABLE IF NOT EXISTS session_journal (
          id           INTEGER PRIMARY KEY,
          workspace_id TEXT NOT NULL,
          ts           INTEGER NOT NULL,
          kind         TEXT NOT NULL,
          detail       TEXT
        )""",
        "CREATE INDEX IF NOT EXISTS idx_journal_ws ON session_journal(workspace_id, id)"
    };

    private Schema() {}

    static void migrate(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String ddl : DDL_V1) st.execute(ddl);
        }
        int version = readVersion(conn);
        if (version > CURRENT_VERSION)
            throw new SQLException("Database schema version " + version
                    + " is newer than this binary supports (" + CURRENT_VERSION + ")");
        try (Statement st = conn.createStatement()) {
            if (version <= 1) for (String ddl : DDL_V2) st.execute(ddl);
            if (version <= 2) for (String ddl : DDL_V3) st.execute(ddl);
            st.execute("INSERT OR REPLACE INTO kv(k, v) VALUES ('schema_version', '"
                    + CURRENT_VERSION + "')");
        }
        // Future versions: apply incremental DDL between `version` and CURRENT_VERSION here.
    }

    private static int readVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT v FROM kv WHERE k = 'schema_version'")) {
            return rs.next() ? Integer.parseInt(new String(rs.getBytes(1))) : 0;
        }
    }
}
