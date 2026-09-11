package io.tokenguard.distill;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.store.Database;
import io.tokenguard.trace.TraceLayer;
import io.tokenguard.trace.TraceStore;
import io.tokenguard.util.Json;
import io.tokenguard.util.TokenEstimator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3.3 DoD: a 5-operation session is restorable by a FRESH journal instance
 * (same workspace key, new process semantics) in < 500 estimated tokens; the
 * journal is capped with oldest-first eviction.
 */
class SessionJournalTest {

    @TempDir Path tmp;
    private Database db;

    @BeforeEach void open() throws Exception { db = Database.open(tmp.resolve("guard.db")); }
    @AfterEach void close() { db.close(); }

    private static Tool passthrough(String name, boolean error) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "t"; }
            @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
            @Override public ToolResult call(ObjectNode args) {
                return error ? ToolResult.error("boom") : ToolResult.text("ok");
            }
        };
    }

    @Test
    void fiveOperationSessionIsRestoredByAFreshInstance() {
        SessionJournal journal = new SessionJournal(db, "ws-j", 200);
        ToolRouter router = new ToolRouter();
        router.register(passthrough("read_file", false));
        router.register(passthrough("apply_edit", false));
        router.register(passthrough("search_files", false));
        router.register(passthrough("query_codebase_structure", true));
        TraceLayer trace = new TraceLayer(new TraceStore(db), () -> true);
        trace.setJournal(journal);
        router.addLayer(trace);

        ObjectNode read = Json.obj();
        read.put("path", "src/App.java");
        router.call("read_file", read);
        ObjectNode read2 = Json.obj();
        read2.put("path", "pom.xml");
        router.call("read_file", read2);
        ObjectNode edit = Json.obj();
        edit.put("path", "src/App.java");
        router.call("apply_edit", edit);
        router.call("search_files", Json.obj());
        router.call("query_codebase_structure", Json.obj()); // -> error row

        // fresh instance = post-compaction / post-restart process
        SessionJournal restored = new SessionJournal(db, "ws-j", 200);
        String summary = new RestoreContextTool(restored).call(Json.obj()).text();

        assertTrue(summary.contains("src/App.java"), summary);
        assertTrue(summary.contains("pom.xml"), summary);
        assertTrue(summary.contains("edited: src/App.java"), summary);
        assertTrue(summary.contains("search_files"), summary);
        assertTrue(summary.contains("query_codebase_structure") && summary.contains("failed"),
                summary);
        assertTrue(TokenEstimator.estimate(summary) < 500,
                "summary must stay dense: " + TokenEstimator.estimate(summary));
    }

    @Test
    void journalIsCappedWithOldestFirstEviction() {
        SessionJournal journal = new SessionJournal(db, "ws-cap", 200);
        for (int i = 0; i < 250; i++) journal.record("read", "file_" + i + ".java");
        assertEquals(200, journal.eventCount(), "cap must hold");
        String summary = journal.render();
        assertFalse(summary.contains("file_0.java"), "oldest evicted");
        assertTrue(summary.contains("file_249.java"), "newest kept");
    }

    @Test
    void workspacesAreIsolated() {
        new SessionJournal(db, "ws-a", 200).record("read", "a.java");
        SessionJournal b = new SessionJournal(db, "ws-b", 200);
        assertEquals(0, b.eventCount());
        assertTrue(b.render().contains("No prior session activity"));
    }
}
