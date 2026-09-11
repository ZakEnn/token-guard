package io.tokenguard.astindex;

import io.tokenguard.store.Database;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.PathJail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AstIndexTest {

    @TempDir Path workspace;
    @TempDir Path dataDir;

    Database db;
    WorkspaceIndexer indexer;
    QueryEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        Path pkg = workspace.resolve("src/main/java/com/acme");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("OrderService.java"), """
                package com.acme;

                import java.util.List;

                /** Handles order lifecycle. Second sentence should be dropped. */
                public class OrderService implements Runnable {
                    private final List<String> orders = List.of();

                    /** Finds orders by status. */
                    public List<String> findByStatus(String status) {
                        return orders.stream().filter(o -> o.contains(status)).toList();
                    }

                    public void run() {
                        findByStatus("NEW");
                    }
                }
                """);
        Files.writeString(pkg.resolve("OrderController.java"), """
                package com.acme;

                public class OrderController {
                    private final OrderService service = new OrderService();

                    public Object list(String status) {
                        return service.findByStatus(status);
                    }
                }
                """);
        Files.writeString(pkg.resolve("Broken.java"), """
                package com.acme;
                public class Broken {
                    public void method( {   // syntax error on purpose
                """);
        db = Database.open(dataDir.resolve("guard.db"));
        PathJail jail = new PathJail(workspace);
        indexer = new WorkspaceIndexer(db, jail, new IgnoreRules(workspace), "ws");
        engine = new QueryEngine(db, "ws");
        indexer.refresh();
    }

    @AfterEach
    void tearDown() { db.close(); }

    @Test
    void indexesGoodFilesDespiteBrokenOne() {
        WorkspaceIndexer.Stats stats = indexer.stats();
        assertEquals(2, stats.ok(), "two clean files");
        assertTrue(stats.failed() + stats.partial() >= 1, "broken file recorded, not fatal (§6.7.3)");
        assertTrue(stats.symbols() > 5);
    }

    @Test
    void outlineReturnsSignaturesNotBodies() {
        QueryEngine.Answer a = engine.query("outline",
                "src/main/java/com/acme/OrderService.java", 1);
        assertTrue(a.text().contains("class com.acme.OrderService"));
        assertTrue(a.text().contains("findByStatus(String status)"));
        assertTrue(a.text().contains("Finds orders by status."));
        assertFalse(a.text().contains("orders.stream()"), "bodies must not leak into outlines");
        assertEquals(1, a.deps().size());
    }

    @Test
    void symbolQueryFindsBySimpleName() {
        QueryEngine.Answer a = engine.query("symbol", "OrderService", 1);
        assertTrue(a.text().contains("class com.acme.OrderService"));
        assertTrue(a.text().contains("OrderService.java:"));
    }

    @Test
    void callersFindsCrossFileCall() {
        QueryEngine.Answer a = engine.query("callers", "findByStatus", 1);
        assertTrue(a.text().contains("OrderController#list"),
                "cross-file caller must be found: " + a.text());
        assertTrue(a.text().contains("OrderService#run"),
                "same-file caller must be found: " + a.text());
    }

    @Test
    void calleesListsOutgoingCalls() {
        QueryEngine.Answer a = engine.query("callees", "com.acme.OrderController#list(String)", 1);
        assertTrue(a.text().contains("findByStatus"), a.text());
    }

    @Test
    void hierarchyShowsInterface() {
        QueryEngine.Answer a = engine.query("hierarchy", "OrderService", 1);
        assertTrue(a.text().contains("Runnable"), a.text());
    }

    @Test
    void searchMatchesSubstring() {
        QueryEngine.Answer a = engine.query("search", "order", 1);
        assertTrue(a.text().contains("com.acme.OrderService"));
        assertTrue(a.text().contains("com.acme.OrderController"));
    }

    @Test
    void incrementalRefreshOnlyReparsesChangedFile() throws Exception {
        WorkspaceIndexer.Stats before = indexer.stats();
        Path file = workspace.resolve("src/main/java/com/acme/OrderService.java");
        String content = Files.readString(file);
        Files.writeString(file, content.replace("findByStatus", "findByState"));
        indexer.refresh();
        QueryEngine.Answer a = engine.query("symbol", "findByState", 1);
        assertTrue(a.text().contains("findByState"), "index must reflect the edit");
        assertEquals(before.symbols(), indexer.stats().symbols(), "same symbol count after rename");
    }

    @Test
    void fixingBrokenFilePromotesItToOk() throws Exception {
        Files.writeString(workspace.resolve("src/main/java/com/acme/Broken.java"), """
                package com.acme;
                public class Broken {
                    public void method() {}
                }
                """);
        indexer.refresh();
        assertEquals(3, indexer.stats().ok());
        assertEquals(0, indexer.stats().failed());
    }

    @Test
    void deletedFileLeavesIndex() throws Exception {
        Files.delete(workspace.resolve("src/main/java/com/acme/OrderController.java"));
        indexer.refresh();
        QueryEngine.Answer a = engine.query("symbol", "OrderController", 1);
        assertTrue(a.text().contains("No symbol"), a.text());
    }
}
