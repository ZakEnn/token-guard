package io.tokenguard.cache;

import io.tokenguard.store.Database;
import io.tokenguard.util.Hashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.7 (plan §6.1.4 test matrix, CLAUDE.md §4.4 debt): lazy per-entry
 * dependency validation — editing a dependency invalidates EXACTLY the
 * entries that depend on it; unrelated edits invalidate nothing; a deleted
 * dependency is a miss.
 */
class DependencyInvalidationTest {

    @TempDir Path workspace;
    @TempDir Path dataDir;
    private Database db;
    private CacheStore store;
    private Path fileA;
    private Path fileB;

    private static final byte[] KEY_A = {1};
    private static final byte[] KEY_B = {2};

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(dataDir.resolve("guard.db"));
        store = new CacheStore(db, workspace, 100 * 1024 * 1024);
        fileA = Files.writeString(workspace.resolve("a.txt"), "content A v1");
        fileB = Files.writeString(workspace.resolve("b.txt"), "content B v1");
        store.store("t", KEY_A, "ws", "answer-from-A", 5, 2, 3600,
                Map.of("a.txt", Hashes.sha256(Files.readAllBytes(fileA))), null, null);
        store.store("t", KEY_B, "ws", "answer-from-B", 5, 2, 3600,
                Map.of("b.txt", Hashes.sha256(Files.readAllBytes(fileB))), null, null);
    }

    @AfterEach void tearDown() { db.close(); }

    @Test
    void unchangedDependenciesHit() {
        assertTrue(store.lookup(KEY_A).isPresent());
        assertTrue(store.lookup(KEY_B).isPresent());
    }

    @Test
    void editingADependencyInvalidatesExactlyItsEntries() throws Exception {
        Files.writeString(fileA, "content A v2 — CHANGED");
        assertTrue(store.lookup(KEY_A).isEmpty(),
                "entry depending on the edited file must be invalidated");
        assertTrue(store.lookup(KEY_B).isPresent(),
                "entry depending on an UNRELATED file must survive (§6.1.4)");
        assertEquals(1, store.entryCount(), "stale entry must be deleted, not just skipped");
    }

    @Test
    void unrelatedNewFilesInvalidateNothing() throws Exception {
        Files.writeString(workspace.resolve("c.txt"), "brand new file");
        assertTrue(store.lookup(KEY_A).isPresent());
        assertTrue(store.lookup(KEY_B).isPresent());
    }

    @Test
    void deletedDependencyIsAMiss() throws Exception {
        Files.delete(fileA);
        assertTrue(store.lookup(KEY_A).isEmpty());
        assertTrue(store.lookup(KEY_B).isPresent());
    }

    @Test
    void reStoreAfterInvalidationHitsAgain() throws Exception {
        Files.writeString(fileA, "content A v2");
        assertTrue(store.lookup(KEY_A).isEmpty());
        store.store("t", KEY_A, "ws", "answer-from-A-v2", 5, 2, 3600,
                Map.of("a.txt", Hashes.sha256(Files.readAllBytes(fileA))), null, null);
        assertEquals("answer-from-A-v2", store.lookup(KEY_A).orElseThrow().result());
    }
}
