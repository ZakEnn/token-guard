package io.tokenguard.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    @TempDir
    Path tmp;

    @Test
    void openCreatesSchema() throws Exception {
        try (Database db = Database.open(tmp.resolve("data").resolve("guard.db"))) {
            List<String> tables = db.with(c -> {
                List<String> names = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
                    while (rs.next()) names.add(rs.getString(1));
                }
                return names;
            });
            assertTrue(tables.containsAll(List.of("cache_entries", "cache_deps", "embeddings",
                    "indexed_files", "symbols", "symbol_edges", "file_state", "metrics_events", "kv")));
        }
    }

    @Test
    void corruptedFileIsMovedAsideAndRecreated() throws Exception {
        Path dbFile = tmp.resolve("guard.db");
        Files.writeString(dbFile, "this is definitely not a sqlite database, padded to look real: "
                + "x".repeat(4096));

        try (Database db = Database.open(dbFile)) {
            db.execute("INSERT INTO kv(k, v) VALUES ('probe', x'01')");
        }

        boolean corruptCopyKept;
        try (var files = Files.list(tmp)) {
            corruptCopyKept = files.anyMatch(p -> p.getFileName().toString().contains(".corrupt."));
        }
        assertTrue(corruptCopyKept, "corrupt file must be kept for support (§6.7.2)");
    }

    @Test
    void reopenPreservesData() throws Exception {
        Path dbFile = tmp.resolve("guard.db");
        try (Database db = Database.open(dbFile)) {
            db.execute("INSERT INTO kv(k, v) VALUES ('answer', '42')");
        }
        try (Database db = Database.open(dbFile)) {
            String v = db.with(c -> {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT v FROM kv WHERE k='answer'")) {
                    return rs.next() ? new String(rs.getBytes(1)) : null;
                }
            });
            assertEquals("42", v);
        }
    }
}
