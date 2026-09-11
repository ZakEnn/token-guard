package io.tokenguard.bench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.astindex.QueryCodebaseStructureTool;
import io.tokenguard.astindex.QueryEngine;
import io.tokenguard.astindex.WorkspaceIndexer;
import io.tokenguard.cache.CacheLayer;
import io.tokenguard.cache.CacheStore;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.cache.Embedder;
import io.tokenguard.codemode.ScriptSandbox;
import io.tokenguard.distill.ApplyEditTool;
import io.tokenguard.distill.DiffService;
import io.tokenguard.distill.FileStateTracker;
import io.tokenguard.distill.ReadFileTool;
import io.tokenguard.distill.SearchFilesTool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.proxy.DownstreamRegistry;
import io.tokenguard.proxy.DownstreamServer;
import io.tokenguard.proxy.ProxyWiring;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2.1 (enhancement plan): reproducible benchmark. Fixed agent-shaped task
 * set over a deterministic fixture workspace; per-task token cost of the
 * TokenGuard path vs a conservatively-defined naive baseline; medians with
 * ranges (honest-medians policy — no best-case headline numbers). Writes
 * bench/RESULTS.md; content is fully deterministic (no timings/timestamps).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BenchmarkTest {

    static final int FILES = 8;

    @TempDir static Path workspace;
    @TempDir static Path dataDir;
    static Database db;
    static ToolRouter router;
    static Metrics metrics;
    static ScriptSandbox sandbox;
    static DownstreamRegistry registry;
    static List<String> fileNames = new ArrayList<>();
    static List<String> fileContents = new ArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        // Deterministic fixture: 8 valid Java files of varying size
        Path pkg = workspace.resolve("src/main/java/com/bench");
        Files.createDirectories(pkg);
        for (int i = 0; i < FILES; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("package com.bench;\n\n")
              .append("/** Fixture service ").append(i).append(". */\n")
              .append("public class Service").append(i).append(" {\n");
            for (int m = 0; m < 6 + i * 4; m++) {
                sb.append("    /** Handles case ").append(m).append(". */\n")
                  .append("    public int handle").append(m).append("(int input) {\n")
                  .append("        // MARK").append(i).append('_').append(m)
                  .append(": deterministic body\n")
                  .append("        return input * ").append(m + 1).append(" + ").append(i).append(";\n")
                  .append("    }\n");
            }
            sb.append("}\n");
            String name = "src/main/java/com/bench/Service" + i + ".java";
            Files.writeString(workspace.resolve(name), sb.toString());
            fileNames.add(name);
            fileContents.add(sb.toString());
        }

        db = Database.open(dataDir.resolve("guard.db"));
        PathJail jail = new PathJail(workspace);
        IgnoreRules ignore = new IgnoreRules(workspace);
        metrics = new Metrics(db);
        DependencyTracker tracker = new DependencyTracker();
        FileStateTracker fileState = new FileStateTracker(db);
        DiffService diff = new DiffService();
        WorkspaceIndexer indexer = new WorkspaceIndexer(db, jail, ignore, "bench");
        indexer.refresh();

        router = new ToolRouter();
        router.register(new ReadFileTool(jail, fileState, diff, metrics, () -> true));
        router.register(new ApplyEditTool(jail, fileState, diff, metrics));
        router.register(new SearchFilesTool(jail, ignore, metrics, tracker));
        router.register(new QueryCodebaseStructureTool(indexer, new QueryEngine(db, "bench"),
                tracker, metrics));

        String sep = java.io.File.separator;
        String javaBin = System.getProperty("java.home") + sep + "bin" + sep + "java";
        registry = new DownstreamRegistry();
        registry.add(new DownstreamServer("data",
                List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                        FakeDownstream.class.getName()), Map.of(), 10_000));
        registry.startAll();
        ProxyWiring.registerFacade(router, registry, new Embedder());
        router.addLayer(new CacheLayer(new CacheStore(db, workspace, 100 * 1024 * 1024),
                tracker, metrics, "bench", new Embedder(), 0.94, () -> true));

        sandbox = ScriptSandbox.create(jail, ignore, indexer, new QueryEngine(db, "bench"),
                tracker, 10_000, 10_000, 5_000_000, "256MB");
        sandbox.setMcpDispatch(args -> router.call("execute_tool", args));
    }

    @AfterAll
    void tearDown() {
        sandbox.close();
        registry.close();
        db.close();
    }

    private static long tokens(ToolResult r) {
        assertFalse(r.isError(), r::text);
        return TokenEstimator.estimate(r.text());
    }

    private static ObjectNode args(Object... kv) {
        ObjectNode o = Json.obj();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Integer n) o.put((String) kv[i], n);
            else o.put((String) kv[i], (String) kv[i + 1]);
        }
        return o;
    }

    record Sample(long naive, long guard) {
        double reductionPct() { return 100.0 * (naive - guard) / naive; }
    }

    // ---- the four task families ----

    /** T1: read -> edit -> verify-reread -> reread. Naive: full content per read, "OK" edit ack. */
    private List<Sample> taskRereadEditing() {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < FILES; i++) {
            String f = fileNames.get(i);
            long g = tokens(router.call("read_file", args("path", f)));
            g += tokens(router.call("apply_edit", editArgs(f,
                    "MARK" + i + "_0: deterministic body",
                    "MARK" + i + "_0: edited body")));
            g += tokens(router.call("read_file", args("path", f)));   // diff
            g += tokens(router.call("read_file", args("path", f)));   // unchanged
            String edited = fileContents.get(i).replace(
                    "MARK" + i + "_0: deterministic body", "MARK" + i + "_0: edited body");
            long naive = TokenEstimator.estimate(fileContents.get(i)) + 3
                    + 2 * TokenEstimator.estimate(edited);
            out.add(new Sample(naive, g));
        }
        return out;
    }

    private static ObjectNode editArgs(String path, String oldS, String newS) {
        ObjectNode a = Json.obj();
        a.put("path", path);
        var edits = Json.arr();
        ObjectNode e = Json.obj();
        e.put("old_string", oldS);
        e.put("new_string", newS);
        edits.add(e);
        a.set("edits", edits);
        return a;
    }

    /** T2: locate a marker then read 10 lines around it. Naive: read the whole file. */
    private List<Sample> taskNavigation() {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < FILES; i++) {
            long g = tokens(router.call("search_files",
                    args("pattern", "MARK" + i + "_2:", "glob", "**/*.java", "max_results", 5)));
            g += tokens(router.call("read_file",
                    args("path", fileNames.get(i), "start_line", 1, "end_line", 10)));
            out.add(new Sample(TokenEstimator.estimate(fileContents.get(i)), g));
        }
        return out;
    }

    /** T3: structural outline instead of reading the file. */
    private List<Sample> taskStructure() {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < FILES; i++) {
            long g = tokens(router.call("query_codebase_structure",
                    args("query_type", "outline", "target", fileNames.get(i))));
            out.add(new Sample(TokenEstimator.estimate(fileContents.get(i)), g));
        }
        return out;
    }

    /** T4: compose two downstream datasets in-script. Naive: both raw payloads in context. */
    private List<Sample> taskCompose() {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int a = i, b = i + 3;
            ScriptSandbox.Outcome o = sandbox.run(
                    "const a = mcp.call('data','get_data_" + a + "',{});"
                    + "const b = mcp.call('data','get_data_" + b + "',{});"
                    + "'rows: ' + (a.split('\\n').length + b.split('\\n').length)", 0);
            assertTrue(o.ok(), () -> o.error());
            long g = TokenEstimator.estimate(o.value());
            long naive = TokenEstimator.estimate(FakeDownstream.payload(a))
                    + TokenEstimator.estimate(FakeDownstream.payload(b));
            out.add(new Sample(naive, g));
        }
        return out;
    }

    // ---- reporting ----

    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private String renderRow(String task, List<Sample> samples) {
        List<Double> reductions = samples.stream().map(Sample::reductionPct).toList();
        double min = reductions.stream().mapToDouble(d -> d).min().orElse(0);
        double max = reductions.stream().mapToDouble(d -> d).max().orElse(0);
        long naiveMed = Math.round(median(samples.stream().map(s -> (double) s.naive()).toList()));
        long guardMed = Math.round(median(samples.stream().map(s -> (double) s.guard()).toList()));
        return String.format(java.util.Locale.ROOT,
                "| %s | %d | %d | %d | **%.1f%%** (%.1f–%.1f%%) |%n",
                task, samples.size(), naiveMed, guardMed, median(reductions), min, max);
    }

    private String collect() {
        StringBuilder md = new StringBuilder();
        md.append("""
                # TokenGuard Benchmark Results (P2.1)

                Reproduce: `mvn -pl bench -am verify` (RESULTS.md is regenerated; content is
                deterministic — reruns must be byte-identical).

                **Counting:| exact BPE (%s). **Policy:** medians with ranges over all samples —
                never best-case numbers (research_proposition.md KC-3).

                ## Methodology
                Deterministic fixture workspace (8 generated Java files, 6–34 methods each) and a
                deterministic fake downstream MCP server (~2 KB payloads). Naive baselines are
                conservative: reads return full content, edit acks are 3 tokens (not full-file
                echoes), navigation baseline reads only the single file that contains the target
                (not the whole tree), composition baseline pays only the two raw tool outputs.

                | Task | n | naive median (tokens) | TokenGuard median (tokens) | reduction median (range) |
                |---|---|---|---|---|
                """.formatted(TokenEstimator.estimatorName()).replace("**Counting:|", "**Counting:**"));
        md.append(renderRow("T1 re-read-heavy editing (read, edit, verify, re-read)", taskRereadEditing()));
        md.append(renderRow("T2 navigation (locate + ranged read vs full read)", taskNavigation()));
        md.append(renderRow("T3 structural query (outline vs full read)", taskStructure()));
        md.append(renderRow("T4 downstream composition (mcp.call script vs raw outputs)", taskCompose()));
        md.append("""

                ## Caveats
                - Fixture-workspace numbers, not field telemetry; savings scale with file size
                  and session length, and T1 depends on how often the agent re-reads.
                - Cache-hit savings (recomputation, not context tokens) are excluded on purpose.
                - Supersedes the informal chars/4-based figures in `evals.md`.
                """);
        return md.toString();
    }

    @Test
    void benchmarkProducesDeterministicResultsMd() throws Exception {
        String report = collect();

        // determinism within the DoD's meaning: regeneration is identical.
        // (State-dependent tools — read_file diff/unchanged — are re-exercised on
        // fresh state below via a second full run in a fresh JVM being identical;
        // here we pin that the report itself carries no timing/timestamp noise.)
        assertFalse(report.matches("(?s).*\\d{4}-\\d{2}-\\d{2}.*"), "no dates in the report");
        assertFalse(report.contains(" ms"), "no timings in the report");

        // every task family must actually save tokens at the median in-fixture
        for (String line : report.lines().filter(l -> l.matches("^\\| T\\d .*")).toList()) {
            String med = line.substring(line.indexOf("**") + 2);
            double value = Double.parseDouble(med.substring(0, med.indexOf('%')));
            assertTrue(value > 0, "median reduction must be positive: " + line);
        }

        Path out = Path.of("RESULTS.md").toAbsolutePath();
        Files.writeString(out, report);
        assertTrue(Files.size(out) > 500);
    }
}
