package io.tokenguard.cache;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SemanticCacheTest {

    @TempDir Path workspace;
    @TempDir Path dataDir;

    Database db;
    ToolRouter router;
    AtomicInteger executions;

    /** Tool with a free-text 'query' field and an identity-bearing 'scope' field. */
    class FuzzyTool implements Tool {
        @Override public String name() { return "fuzzy"; }
        @Override public String description() { return "test"; }
        @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
        @Override public CachePolicy cachePolicy() { return CachePolicy.SEARCH_RESULT; }
        @Override public String semanticText(ObjectNode args) { return Json.str(args, "query", null); }
        @Override public List<String> semanticFields() { return List.of("query"); }

        @Override public ToolResult call(ObjectNode args) {
            executions.incrementAndGet();
            return ToolResult.text("answer for: " + Json.str(args, "query", "")
                    + " in " + Json.str(args, "scope", "-"), 500);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(dataDir.resolve("guard.db"));
        executions = new AtomicInteger();
        router = new ToolRouter();
        router.addLayer(new CacheLayer(new CacheStore(db, workspace, 1024 * 1024),
                new DependencyTracker(), new Metrics(db), "ws", new Embedder(), 0.94,
                () -> true));
        router.register(new FuzzyTool());
    }

    @AfterEach
    void tearDown() { db.close(); }

    private ToolResult call(String query, String scope) {
        ObjectNode args = Json.obj();
        args.put("query", query);
        args.put("scope", scope);
        return router.call("fuzzy", args);
    }

    @Test
    void reorderedQueryHitsSemanticTier() {
        ToolResult first = call("order service find methods", "src");
        ToolResult second = call("find methods order service", "src");
        assertEquals(1, executions.get(), "word reorder must hit Tier 2");
        assertEquals(first.text(), second.text());
    }

    @Test
    void punctuationAndCaseVariantsHit() {
        call("Find the OrderService methods", "src");
        call("find the orderservice methods!", "src");
        assertEquals(1, executions.get());
    }

    @Test
    void poisonedPairChangedIdentifierMustMiss() {
        call("find users in module alpha", "src");
        call("find users in module beta", "src");
        assertEquals(2, executions.get(),
                "one changed identifier must NOT match (§6.1.1 precision rule)");
    }

    @Test
    void differentIdentityArgNeverMatchesDespiteIdenticalText() {
        call("find all order methods", "src");
        call("find all order methods", "test");
        assertEquals(2, executions.get(),
                "identity args (scope) must partition the semantic space");
    }

    @Test
    void unrelatedQueryMustMiss() {
        call("list database migrations", "src");
        call("show http endpoint handlers", "src");
        assertEquals(2, executions.get());
    }

    @Test
    void semanticHitRespectsTtl() {
        call("find order methods", "src");
        db.execute("UPDATE cache_entries SET created_at = created_at - 999999");
        call("order methods find", "src");
        assertEquals(2, executions.get(), "expired entries must not serve via Tier 2 either");
    }
}
