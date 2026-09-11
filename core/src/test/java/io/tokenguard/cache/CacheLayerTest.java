package io.tokenguard.cache;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.Hashes;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CacheLayerTest {

    @TempDir Path workspace;
    @TempDir Path dataDir;

    Database db;
    DependencyTracker tracker;
    ToolRouter router;
    AtomicInteger executions;
    CacheStore store;

    /** Cacheable tool that reads dep.txt and counts real executions. */
    class CountingTool implements Tool {
        @Override public String name() { return "counting"; }
        @Override public String description() { return "test"; }
        @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
        @Override public CachePolicy cachePolicy() { return CachePolicy.SEARCH_RESULT; }

        @Override public ToolResult call(ObjectNode args) throws Exception {
            executions.incrementAndGet();
            Path dep = workspace.resolve("dep.txt");
            byte[] bytes = Files.readAllBytes(dep);
            tracker.recordFile("dep.txt", Hashes.sha256(bytes));
            return ToolResult.text("result-over:" + new String(bytes).trim(), 1000);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(workspace.resolve("dep.txt"), "v1\n");
        Files.writeString(workspace.resolve("unrelated.txt"), "x\n");
        db = Database.open(dataDir.resolve("guard.db"));
        tracker = new DependencyTracker();
        executions = new AtomicInteger();
        store = new CacheStore(db, workspace, 1024 * 1024);
        router = new ToolRouter();
        router.addLayer(new CacheLayer(store, tracker, new Metrics(db), "ws-test",
                new Embedder(), 0.94, () -> true));
        router.register(new CountingTool());
    }

    @AfterEach
    void tearDown() { db.close(); }

    private ToolResult call() {
        return router.call("counting", Json.obj());
    }

    @Test
    void secondIdenticalCallIsServedFromCache() {
        ToolResult first = call();
        ToolResult second = call();
        assertEquals(first.text(), second.text());
        assertEquals(1, executions.get(), "second call must not execute the tool");
    }

    @Test
    void editingDependencyInvalidatesEntry() throws Exception {
        call();
        Files.writeString(workspace.resolve("dep.txt"), "v2\n");
        ToolResult r = call();
        assertEquals(2, executions.get(), "changed dep must force re-execution (§6.1.4)");
        assertTrue(r.text().contains("v2"));
    }

    @Test
    void editingUnrelatedFileKeepsEntry() throws Exception {
        call();
        Files.writeString(workspace.resolve("unrelated.txt"), "changed\n");
        call();
        assertEquals(1, executions.get(), "unrelated edits must not invalidate (§6.1.4)");
    }

    @Test
    void deletedDependencyInvalidatesEntry() throws Exception {
        call();
        Files.delete(workspace.resolve("dep.txt"));
        Files.writeString(workspace.resolve("dep.txt"), "v3\n");
        call();
        assertEquals(2, executions.get());
    }

    @Test
    void expiredTtlIsAMiss() throws Exception {
        call();
        db.execute("UPDATE cache_entries SET created_at = created_at - 999999");
        call();
        assertEquals(2, executions.get(), "expired entries must not serve (§6.1.3 lazy TTL)");
    }

    @Test
    void errorsAreNeverCached() {
        router.register(new CountingTool() {
            @Override public String name() { return "failing"; }
            @Override public ToolResult call(ObjectNode args) {
                executions.incrementAndGet();
                return ToolResult.error("nope");
            }
        });
        router.call("failing", Json.obj());
        router.call("failing", Json.obj());
        assertEquals(2, executions.get());
        assertEquals(0, store.entryCount());
    }

    @Test
    void evictionDropsLowestValueDensityFirst() throws Exception {
        // Budget fits ~2 of 3 entries; entry values differ via saved_tokens
        CacheStore tiny = new CacheStore(db, workspace, 250);
        String filler = "x".repeat(100);
        tiny.store("t", Hashes.sha256("k1"), "ws", filler, 10, 5, 3600, java.util.Map.of(), null, null);
        tiny.store("t", Hashes.sha256("k2"), "ws", filler, 10, 5000, 3600, java.util.Map.of(), null, null);
        tiny.store("t", Hashes.sha256("k3"), "ws", filler, 10, 4000, 3600, java.util.Map.of(), null, null);
        assertEquals(2, tiny.entryCount(), "over budget must evict");
        assertTrue(tiny.lookup(Hashes.sha256("k1")).isEmpty(), "lowest score evicted first");
        assertTrue(tiny.lookup(Hashes.sha256("k2")).isPresent());
    }
}
