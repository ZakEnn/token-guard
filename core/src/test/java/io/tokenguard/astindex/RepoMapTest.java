package io.tokenguard.astindex;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.Json;
import io.tokenguard.util.PathJail;
import io.tokenguard.util.TokenEstimator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3.2 DoD: repo_map stays within its token budget, ranks the reference hub
 * above unreferenced leaves, truncates with an explicit note under a tight
 * budget, and the negative-ROI guard redirects to read_file when the
 * structural answer would cost more than the raw read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepoMapTest {

    @TempDir static Path workspace;
    @TempDir static Path dataDir;
    static Database db;
    static WorkspaceIndexer indexer;
    static QueryEngine engine;

    @BeforeAll
    void setUp() throws Exception {
        Path pkg = workspace.resolve("src/com/acme");
        Files.createDirectories(pkg);
        StringBuilder hub = new StringBuilder("package com.acme;\npublic class Hub {\n");
        for (int m = 0; m < 6; m++)
            hub.append("    public int hubMethod").append(m).append("(int x) {\n")
               .append("        int acc = x;\n")
               .append("        for (int i = 0; i < ").append(m + 2).append("; i++) {\n")
               .append("            acc = acc * 31 + i; // deterministic body line ").append(m).append('\n')
               .append("            acc = acc ^ (acc >>> 7);\n")
               .append("        }\n")
               .append("        return acc + ").append(m).append(";\n")
               .append("    }\n");
        hub.append("}\n");
        Files.writeString(pkg.resolve("Hub.java"), hub.toString());
        for (int i = 1; i <= 4; i++) {
            Files.writeString(pkg.resolve("Leaf" + i + ".java"), """
                    package com.acme;
                    public class Leaf%d {
                        public int use() { return new Hub().hubMethod%d(7); }
                        public int useMore() { return new Hub().hubMethod0(1); }
                    }
                    """.formatted(i, i));
        }
        Files.writeString(pkg.resolve("Isolated.java"), """
                package com.acme;
                public class Isolated {
                    public void nothing() { }
                }
                """);
        // Signature-dense file: the outline costs at least as much as the read
        Files.writeString(pkg.resolve("Wide.java"), """
                package com.acme;
                public interface Wide {
                    int extraordinarilyDescriptiveOperationNumberOne(int firstOperand, int secondOperand);
                    int extraordinarilyDescriptiveOperationNumberTwo(int firstOperand, int secondOperand);
                    int extraordinarilyDescriptiveOperationNumberThree(int firstOperand, int secondOperand);
                    int extraordinarilyDescriptiveOperationNumberFour(int firstOperand, int secondOperand);
                    int extraordinarilyDescriptiveOperationNumberFive(int firstOperand, int secondOperand);
                    int extraordinarilyDescriptiveOperationNumberSix(int firstOperand, int secondOperand);
                }
                """);

        db = Database.open(dataDir.resolve("guard.db"));
        indexer = new WorkspaceIndexer(db, new PathJail(workspace),
                new IgnoreRules(workspace), "rm");
        indexer.refresh();
        engine = new QueryEngine(db, "rm");
    }

    @AfterAll
    void tearDown() { db.close(); }

    @Test
    void repoMapRespectsBudgetAndRanksHubFirst() {
        QueryEngine.Answer a = engine.repoMap(1000);
        long tokens = TokenEstimator.estimate(a.text());
        assertTrue(tokens <= 1000, "map must fit its budget, got " + tokens);
        int hub = a.text().indexOf("Hub.java");
        int isolated = a.text().indexOf("Isolated.java");
        assertTrue(hub >= 0, "hub file must be present:\n" + a.text());
        assertTrue(isolated < 0 || hub < isolated,
                "the referenced hub must rank above the unreferenced leaf:\n" + a.text());
        assertFalse(a.deps().isEmpty(), "map must carry its invalidation set");
    }

    @Test
    void tightBudgetTruncatesWithExplicitNote() {
        QueryEngine.Answer a = engine.repoMap(200);
        long tokens = TokenEstimator.estimate(a.text());
        assertTrue(tokens <= 200, "tight budget must hold, got " + tokens);
        assertTrue(a.text().contains("more files omitted"),
                "truncation must be labeled, never silent:\n" + a.text());
    }

    @Test
    void repoMapIsDeterministic() {
        assertEquals(engine.repoMap(800).text(), engine.repoMap(800).text());
    }

    @Test
    void negativeRoiGuardRedirectsToReadFile() {
        QueryCodebaseStructureTool tool = new QueryCodebaseStructureTool(indexer, engine,
                new DependencyTracker(), new Metrics(db));
        ObjectNode args = Json.obj();
        args.put("query_type", "outline");
        args.put("target", "src/com/acme/Wide.java");
        ToolResult r = tool.call(args);
        assertFalse(r.isError());
        assertTrue(r.text().contains("use read_file"),
                "guard must redirect when structure costs >= raw read:\n" + r.text());
        assertTrue(TokenEstimator.estimate(r.text()) < 80,
                "the redirect itself must be cheap");
    }

    @Test
    void normalOutlineIsUntouchedByTheGuard() {
        QueryCodebaseStructureTool tool = new QueryCodebaseStructureTool(indexer, engine,
                new DependencyTracker(), new Metrics(db));
        ObjectNode args = Json.obj();
        args.put("query_type", "outline");
        args.put("target", "src/com/acme/Hub.java");
        ToolResult r = tool.call(args);
        assertFalse(r.isError());
        assertTrue(r.text().contains("hubMethod0"), "real outlines still flow:\n" + r.text());
    }
}
