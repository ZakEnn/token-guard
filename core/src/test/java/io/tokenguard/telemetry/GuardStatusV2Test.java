package io.tokenguard.telemetry;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2.2 DoD: status carries the per-tier cache split and the trace-derived
 * session detail; no "$" appears unless a price is configured; metrics and
 * trace-table sums reconcile for the same calls.
 */
class GuardStatusV2Test {

    @TempDir Path tmp;
    private Database db;

    @BeforeEach void open() throws Exception { db = Database.open(tmp.resolve("guard.db")); }
    @AfterEach void close() { db.close(); }

    private static Tool fixed(String name, String text, long raw) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "t"; }
            @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
            @Override public ToolResult call(ObjectNode args) {
                return new ToolResult(text, false, raw);
            }
        };
    }

    @Test
    void tierSplitAppearsInSavingsSection() {
        Metrics metrics = new Metrics(db);
        metrics.cacheHit("exact");
        metrics.cacheHit("exact");
        metrics.cacheHit("semantic");
        metrics.cacheMiss();
        String s = metrics.renderSession();
        assertTrue(s.contains("3/4 hits"), s);
        assertTrue(s.contains("[exact 2, semantic 1, team 0]"), s);
        assertTrue(s.contains("cl100k_base"), "estimator must be named: " + s);
    }

    @Test
    void metricsAndTraceReconcileAndNoDollarWithoutPrice() {
        Metrics metrics = new Metrics(db);
        TraceStore store = new TraceStore(db);
        TraceLayer trace = new TraceLayer(store, () -> true);
        trace.setClient("recon-client", "2.0");

        ToolRouter router = new ToolRouter();
        String text = "result payload with some content";
        long raw = 500;
        router.register(fixed("read_file", text, raw));
        // metrics layer exactly as GuardWiring wires it
        router.addLayer((tool, args, next) -> {
            metrics.toolCall(tool.name());
            ToolResult r = next.invoke(tool, args);
            metrics.tokens(tool.name(), r.rawTokens(), TokenEstimator.estimate(r.text()));
            return r;
        });
        router.addLayer(trace);

        router.call("read_file", Json.obj());
        router.call("read_file", Json.obj());

        List<TraceStore.Row> rows = store.session(trace.sessionId());
        assertEquals(2, rows.size());
        long traceRaw = rows.stream().mapToLong(TraceStore.Row::rawTokens).sum();
        long traceReturned = rows.stream().mapToLong(TraceStore.Row::returnedTokens).sum();
        assertEquals(metrics.rawTokensTotal(), traceRaw, "raw totals must reconcile");
        assertEquals(metrics.returnedTokensTotal(), traceReturned,
                "returned totals must reconcile");
        assertEquals(metrics.savedTokens(), traceRaw - traceReturned);

        // status text without any configured price must not invent a $ figure
        GuardStatusTool status = new GuardStatusTool("test");
        status.addSection("Savings (this session)", metrics::renderSession);
        String out = status.call(Json.obj()).text();
        assertFalse(out.contains("$"), out);
        assertTrue(out.contains("raw " + traceRaw));

        // session-detail shape (client attribution + own/downstream split)
        assertEquals("recon-client", rows.get(0).clientName());
        assertTrue(rows.stream().noneMatch(r -> r.engine().equals("proxy")));
    }
}
