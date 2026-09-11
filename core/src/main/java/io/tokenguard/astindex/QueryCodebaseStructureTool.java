package io.tokenguard.astindex;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.Json;
import io.tokenguard.util.TokenEstimator;

import java.util.Map;

/**
 * query_codebase_structure (plan §5.1, Engine 2): structural answers instead
 * of raw files — the ~90% reduction lever for code-reading operations. The
 * naive baseline for savings is reading each backing file in full (A6).
 */
public final class QueryCodebaseStructureTool implements Tool {

    private final WorkspaceIndexer indexer;
    private final QueryEngine engine;
    private final DependencyTracker tracker;
    private final Metrics metrics;

    public QueryCodebaseStructureTool(WorkspaceIndexer indexer, QueryEngine engine,
                                      DependencyTracker tracker, Metrics metrics) {
        this.indexer = indexer;
        this.engine = engine;
        this.tracker = tracker;
        this.metrics = metrics;
    }

    @Override public String name() { return "query_codebase_structure"; }

    @Override public String description() {
        return "Query the codebase's structure instead of reading files: class/method outlines, "
                + "signatures, callers/callees, dependencies, type hierarchy, symbol search. "
                + "An outline is a small fraction of the size of the file it summarizes — "
                + "PREFER this over reading whole files to understand code. Java files only.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        ObjectNode qt = Json.prop("string",
                "One of: outline (of a file), symbol (signature + location), callers, callees, "
                        + "dependencies, hierarchy, search (fuzzy symbol name), repo_map "
                        + "(ranked whole-repo signature map under a token budget)");
        props.set("query_type", qt);
        props.set("target", Json.prop("string",
                "File path (outline/dependencies) or type/method name, e.g. 'McpServer' or "
                        + "'io.tokenguard.mcp.McpServer#handleRequest'. Ignored for repo_map."));
        props.set("depth", Json.prop("integer", "Hierarchy depth (default 1)"));
        props.set("map_tokens", Json.prop("integer",
                "repo_map only: token budget for the map (default 1000)"));
        return Json.schemaObject(props, "query_type");
    }

    @Override public CachePolicy cachePolicy() { return CachePolicy.STRUCTURE_QUERY; }

    /** Only the fuzzy 'search' query is free text; all other targets are identities (§6.1.1). */
    @Override public String semanticText(ObjectNode args) {
        return "search".equals(Json.str(args, "query_type", null))
                ? Json.str(args, "target", null) : null;
    }

    @Override public java.util.List<String> semanticFields() { return java.util.List.of("target"); }

    @Override
    public ToolResult call(ObjectNode args) {
        String queryType = Json.str(args, "query_type", "search");
        String target = Json.str(args, "target", "");
        int depth = Json.intVal(args, "depth", 1);

        indexer.refreshIfStale();
        QueryEngine.Answer answer = "repo_map".equals(queryType)
                ? engine.repoMap(Json.intVal(args, "map_tokens", 1000))
                : engine.query(queryType, target, depth);

        for (Map.Entry<String, byte[]> dep : answer.deps().entrySet()) {
            tracker.recordFile(dep.getKey(), dep.getValue());
        }
        // Naive baseline: the agent would have read each backing file in full
        long naive = TokenEstimator.estimateBytes(answer.depsBytes());
        long returned = TokenEstimator.estimate(answer.text());

        // P3.2 negative-ROI guard (research: code-review-graph's admission —
        // structural metadata can exceed a naive read on small files): when the
        // structural answer would cost >= reading the backing file(s) directly,
        // return a short redirect instead — the cheapest representation of all,
        // and read_file's diff logic then applies to the actual read.
        if ("outline".equals(queryType) && naive > 0 && returned >= naive) {
            String paths = answer.deps().keySet().stream().limit(3)
                    .reduce((a, b) -> a + ", " + b).orElse(target);
            String note = "[structural answer (~" + returned + " tokens) would cost more than "
                    + "reading the file directly (~" + naive + " tokens) - use read_file on "
                    + paths + "]";
            metrics.tokens(name(), naive, TokenEstimator.estimate(note));
            return ToolResult.text(note, naive);
        }
        metrics.tokens(name(), Math.max(naive, returned), returned);
        return ToolResult.text(answer.text(), Math.max(naive, returned));
    }
}
