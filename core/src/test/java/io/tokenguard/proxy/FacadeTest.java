package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.cache.CacheLayer;
import io.tokenguard.cache.CacheStore;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.cache.Embedder;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1.2 DoD: with a >=40-tool downstream registry — facade mode exposes a fixed
 * tool surface; search_tools ranks the right tool first; execute_tool results
 * flow through CacheLayer (2nd identical read-verb call is a hit; mutating
 * verbs are never cached); namespaced mode re-registers everything ds__-
 * prefixed; the facade's schema footprint is independent of registry size.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FacadeTest {

    @TempDir static Path tmp;
    static Database db;
    static DownstreamRegistry registry;
    static Metrics metrics;
    static ToolRouter router;

    private static List<String> fakeCommand(String... variant) {
        String sep = java.io.File.separator;
        String javaBin = System.getProperty("java.home") + sep + "bin" + sep + "java";
        List<String> cmd = new java.util.ArrayList<>(List.of(javaBin, "-cp",
                System.getProperty("java.class.path"), FakeMcpServerMain.class.getName()));
        cmd.addAll(List.of(variant));
        return cmd;
    }

    @BeforeAll
    void setUp() throws Exception {
        db = Database.open(tmp.resolve("guard.db"));
        registry = new DownstreamRegistry();
        registry.add(new DownstreamServer("many", fakeCommand("many", "40"), Map.of(), 5000));
        registry.startAll();
        assertTrue(registry.allTools().size() >= 40,
                "DoD needs >=40 downstream tools, got " + registry.allTools().size());

        metrics = new Metrics(db);
        router = new ToolRouter();
        ProxyWiring.registerFacade(router, registry, new Embedder());
        CacheLayer cache = new CacheLayer(new CacheStore(db, tmp, 100 * 1024 * 1024),
                new DependencyTracker(), metrics, "ws-test", new Embedder(), 0.94, () -> true);
        router.addLayer(cache);
    }

    @AfterAll
    void tearDown() {
        registry.close();
        db.close();
    }

    @Test
    void facadeExposesFixedToolSurface() {
        assertEquals(3, router.list().size(),
                "facade mode: exactly 3 exposed tools regardless of 40 downstream");
    }

    @Test
    void searchToolsRanksTheRightToolFirst() {
        ObjectNode args = Json.obj();
        args.put("query", "translate text between natural languages");
        ToolResult r = router.call("search_tools", args);
        assertFalse(r.isError(), r.text());
        String firstLine = r.text().lines().findFirst().orElse("");
        assertTrue(firstLine.contains("many/translate_text"),
                "expected translate_text ranked first, got:\n" + r.text());
    }

    @Test
    void describeToolsPromotesFullSchemaOnDemand() {
        ObjectNode args = Json.obj();
        args.set("names", Json.arr().add("many/translate_text"));
        ToolResult r = router.call("describe_tools", args);
        assertFalse(r.isError(), r.text());
        assertTrue(r.text().contains("inputSchema"));
        assertTrue(r.text().contains("many/translate_text"));
    }

    @Test
    void executeToolCachesReadVerbsButNeverMutations() {
        // read-shaped: get_* -> SEARCH_RESULT policy -> 2nd identical call hits
        ObjectNode read = Json.obj();
        read.put("name", "many/get_weather_0");
        ObjectNode ra = Json.obj();
        ra.put("msg", "x");
        read.set("arguments", ra);
        assertFalse(router.call("execute_tool", read).isError());
        assertFalse(router.call("execute_tool", read.deepCopy()).isError());
        String session = metrics.renderSession();
        assertTrue(session.contains("1/2 hits"),
                "second identical read must be a cache hit: " + session);

        // mutating: create_* -> CachePolicy.NONE -> cache counters untouched
        ObjectNode mut = Json.obj();
        mut.put("name", "many/create_issue_2");
        mut.set("arguments", ra.deepCopy());
        assertFalse(router.call("execute_tool", mut).isError());
        assertFalse(router.call("execute_tool", mut.deepCopy()).isError());
        assertTrue(metrics.renderSession().contains("1/2 hits"),
                "mutating downstream tools must never be cached");
    }

    @Test
    void namespacedModeReRegistersEverything() {
        ToolRouter nsRouter = new ToolRouter();
        ProxyWiring.registerNamespaced(nsRouter, registry);
        assertTrue(nsRouter.list().size() >= 40);
        assertTrue(nsRouter.has("ds__many__echo"));
        ObjectNode args = Json.obj();
        args.put("msg", "ns");
        assertEquals("echo:ns", nsRouter.call("ds__many__echo", args).text());
    }

    @Test
    void facadeSchemaFootprintIsIndependentOfRegistrySize() {
        DownstreamRegistry empty = new DownstreamRegistry();
        for (Tool[] pair : new Tool[][]{
                {new SearchToolsTool(registry, new Embedder()), new SearchToolsTool(empty, new Embedder())},
                {new DescribeToolsTool(registry), new DescribeToolsTool(empty)},
                {new ExecuteToolTool(registry), new ExecuteToolTool(empty)}}) {
            assertEquals(pair[1].description(), pair[0].description());
            assertEquals(pair[1].inputSchema().toString(), pair[0].inputSchema().toString());
        }
    }
}
