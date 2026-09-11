package io.tokenguard.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.McpServer;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.store.Database;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0.2 DoD: the scripted verification session (initialize + read x2 +
 * guard_status) yields exactly 3 trace rows with correct client attribution,
 * correct engine attribution, the second read labeled unchanged_reread — and a
 * failing trace store never fails the tool call.
 */
class TraceLayerTest {

    @TempDir Path tmp;
    private Database db;

    @BeforeEach void open() throws Exception { db = Database.open(tmp.resolve("guard.db")); }
    @AfterEach void close() { db.close(); }

    /** Mirrors ReadFileTool's documented output contract (full -> unchanged). */
    private static Tool fakeReadFile() {
        AtomicInteger calls = new AtomicInteger();
        return new Tool() {
            @Override public String name() { return "read_file"; }
            @Override public String description() { return "test"; }
            @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
            @Override public ToolResult call(ObjectNode args) {
                return calls.incrementAndGet() == 1
                        ? new ToolResult("pom.xml (lines 1-27 of 27):\n<project/>", false, 185)
                        : new ToolResult("pom.xml: unchanged since you last read it.", false, 185);
            }
        };
    }

    private static Tool fakeStatus() {
        return new Tool() {
            @Override public String name() { return "guard_status"; }
            @Override public String description() { return "test"; }
            @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
            @Override public ToolResult call(ObjectNode args) { return ToolResult.text("ok"); }
        };
    }

    @Test
    void scriptedSessionYieldsAttributedRows() {
        ToolRouter router = new ToolRouter();
        router.register(fakeReadFile());
        router.register(fakeStatus());
        TraceStore store = new TraceStore(db);
        TraceLayer layer = new TraceLayer(store, () -> true);
        router.addLayer(layer);

        // clientInfo flows from the MCP initialize handshake
        McpServer server = new McpServer(router, "test");
        server.setClientInfoListener(layer::setClient);
        ObjectNode init = Json.obj();
        init.put("protocolVersion", "2025-06-18");
        ObjectNode ci = Json.obj();
        ci.put("name", "verification-test");
        ci.put("version", "1.0");
        init.set("clientInfo", ci);
        JsonNode resp = server.handleRequest(Json.obj().numberNode(0), "initialize", init);
        assertFalse(resp.has("error"));

        router.call("read_file", Json.obj());
        router.call("read_file", Json.obj());
        router.call("guard_status", Json.obj());

        List<TraceStore.Row> rows = store.session(layer.sessionId());
        assertEquals(3, rows.size(), "exactly one row per tool call");
        for (TraceStore.Row r : rows) {
            assertEquals("verification-test", r.clientName());
            assertEquals("1.0", r.clientVersion());
            assertFalse(r.wasError());
            assertFalse(r.wasPassthrough());
        }
        assertEquals("diff_distill", rows.get(0).engine());
        assertEquals("diff_distill", rows.get(1).engine());
        assertEquals("telemetry", rows.get(2).engine());
        assertNull(rows.get(0).optimization(), "first read is the full-content baseline");
        assertEquals("unchanged_reread", rows.get(1).optimization(), "second read optimized");
        assertTrue(rows.get(1).returnedTokens() < rows.get(0).rawTokens());
    }

    @Test
    void traceFailureNeverFailsTheToolCall() {
        ToolRouter router = new ToolRouter();
        router.register(fakeStatus());
        Database dead = db;
        dead.close(); // store now writes into a closed connection
        TraceLayer layer = new TraceLayer(new TraceStore(dead), () -> true);
        router.addLayer(layer);

        ToolResult r = router.call("guard_status", Json.obj());
        assertFalse(r.isError(), "trace write failure must be swallowed (§6.7)");
        assertEquals("ok", r.text());
    }

    @Test
    void passthroughAndErrorRowsAreStillTraced() {
        ToolRouter router = new ToolRouter();
        router.register(new Tool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "test"; }
            @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
            @Override public ToolResult call(ObjectNode args) throws Exception {
                throw new IllegalStateException("kaboom");
            }
        });
        TraceStore store = new TraceStore(db);
        TraceLayer layer = new TraceLayer(store, () -> false); // pass-through mode
        router.addLayer(layer);

        ToolResult r = router.call("boom", Json.obj());
        assertTrue(r.isError());
        List<TraceStore.Row> rows = store.session(layer.sessionId());
        assertEquals(1, rows.size(), "tracing is unconditional — errors are rows too");
        assertTrue(rows.get(0).wasError());
        assertTrue(rows.get(0).wasPassthrough());
        assertEquals("other", rows.get(0).engine());
    }
}
