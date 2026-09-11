package io.tokenguard.codemode;

import io.tokenguard.astindex.QueryEngine;
import io.tokenguard.astindex.WorkspaceIndexer;
import io.tokenguard.cache.CacheLayer;
import io.tokenguard.cache.CacheStore;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.cache.Embedder;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.proxy.DownstreamRegistry;
import io.tokenguard.proxy.DownstreamServer;
import io.tokenguard.proxy.FakeMcpServerMain;
import io.tokenguard.proxy.ProxyWiring;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.trace.TraceLayer;
import io.tokenguard.trace.TraceStore;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.PathJail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1.4 DoD: a script composes two downstream tools and returns only the join;
 * the downstream calls are cached (read-verb) and traced via the execute_tool
 * path; a tight mcp.call loop is stopped by the sandbox limits; without a
 * dispatcher the failure is a clear, catchable script error.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpCallTest {

    @TempDir static Path workspace;
    @TempDir static Path dataDir;
    static Database db;
    static DownstreamRegistry registry;
    static ScriptSandbox sandbox;
    static Metrics metrics;
    static TraceLayer traceLayer;
    static TraceStore traceStore;

    @BeforeAll
    void setUp() throws Exception {
        Files.writeString(workspace.resolve("readme.txt"), "hello\n");
        db = Database.open(dataDir.resolve("guard.db"));

        String sep = java.io.File.separator;
        String javaBin = System.getProperty("java.home") + sep + "bin" + sep + "java";
        registry = new DownstreamRegistry();
        registry.add(new DownstreamServer("many",
                List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                        FakeMcpServerMain.class.getName(), "many", "10"),
                Map.of(), 5000));
        registry.startAll();

        // Production-shaped router: facade tools + cache + trace layers
        metrics = new Metrics(db);
        ToolRouter router = new ToolRouter();
        ProxyWiring.registerFacade(router, registry, new Embedder());
        router.addLayer(new CacheLayer(new CacheStore(db, workspace, 100 * 1024 * 1024),
                new DependencyTracker(), metrics, "ws-mcp", new Embedder(), 0.94, () -> true));
        traceStore = new TraceStore(db);
        traceLayer = new TraceLayer(traceStore, () -> true);
        router.addLayer(traceLayer);

        PathJail jail = new PathJail(workspace);
        IgnoreRules ignore = new IgnoreRules(workspace);
        WorkspaceIndexer indexer = new WorkspaceIndexer(db, jail, ignore, "ws-mcp");
        sandbox = ScriptSandbox.create(jail, ignore, indexer, new QueryEngine(db, "ws-mcp"),
                new DependencyTracker(), 2000, 2000, 2_000_000, "256MB");
        sandbox.setMcpDispatch(args -> router.call("execute_tool", args));
    }

    @AfterAll
    void tearDown() {
        sandbox.close();
        registry.close();
        db.close();
    }

    @Test
    void scriptComposesTwoDownstreamCallsAndReturnsOnlyTheJoin() {
        // tests share the session (PER_CLASS registry) — assert the DELTA
        long before = traceStore.session(traceLayer.sessionId()).stream()
                .filter(r -> r.tool().equals("execute_tool")).count();
        ScriptSandbox.Outcome o = sandbox.run("""
                const a = mcp.call('many', 'get_weather_0', {msg: 'first'});
                const b = mcp.call('many', 'get_weather_5', {msg: 'second'});
                a + '|' + b
                """, 0);
        assertTrue(o.ok(), () -> o.error());
        assertEquals("echo:first|echo:second", o.value());

        // both downstream calls traced through the execute_tool path
        List<TraceStore.Row> rows = traceStore.session(traceLayer.sessionId());
        long executeToolRows = rows.stream()
                .filter(r -> r.tool().equals("execute_tool")).count();
        assertEquals(before + 2, executeToolRows,
                "each mcp.call must be a traced execute_tool row");
        assertTrue(rows.stream().allMatch(r -> r.engine().equals("proxy")));
    }

    @Test
    void repeatedReadCallsAreServedFromCache() {
        sandbox.run("mcp.call('many', 'get_weather_1', {msg: 'cached'})", 0);
        sandbox.run("mcp.call('many', 'get_weather_1', {msg: 'cached'})", 0);
        assertTrue(metrics.renderSession().contains("hits"),
                metrics.renderSession());
        // at least one hit must have been recorded for the identical read pair
        assertTrue(metrics.renderSession().matches("(?s).*cache: [1-9]\\d*/\\d+ hits.*"),
                "identical read-verb mcp.call must hit the cache: " + metrics.renderSession());
    }

    @Test
    void tightMcpCallLoopIsStoppedBySandboxLimits() {
        long t0 = System.currentTimeMillis();
        ScriptSandbox.Outcome o = sandbox.run(
                "while (true) { mcp.call('many', 'get_weather_2', {msg: 'x'}); }", 500);
        assertFalse(o.ok());
        assertTrue(o.error().contains("limit"), o.error());
        assertTrue(System.currentTimeMillis() - t0 < 10_000);
        assertFalse(o.engineFault(), "resource kill must not trip the circuit breaker");
    }

    @Test
    void withoutDispatcherTheErrorIsClearAndCatchable(@TempDir Path w2) throws Exception {
        Files.writeString(w2.resolve("f.txt"), "x");
        PathJail jail = new PathJail(w2);
        try (ScriptSandbox bare = ScriptSandbox.create(jail, new IgnoreRules(w2),
                new WorkspaceIndexer(db, jail, new IgnoreRules(w2), "ws-bare"),
                new QueryEngine(db, "ws-bare"), new DependencyTracker(),
                1000, 1000, 1_000_000, "256MB")) {
            ScriptSandbox.Outcome o = bare.run(
                    "try { mcp.call('a','b',{}) } catch (e) { 'caught: ' + e.message }", 0);
            assertTrue(o.ok(), () -> o.error());
            assertTrue(o.value().contains("caught:"), o.value());
            assertTrue(o.value().contains("no downstream MCP servers"), o.value());
        }
    }
}
